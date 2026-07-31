package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedIteratorDetector;
import se.deversity.asynctest.example.service.WorkQueueService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for WorkQueueService.
 *
 * ========================================================================
 * DETECTOR: SharedIteratorDetector  (DetectorType.SHARED_ITERATOR)
 * ========================================================================
 *
 * An Iterator is a cursor, not a queue. hasNext() and next() are two reads
 * of mutable state with a gap between them, and the gap is where another
 * thread takes the element this one just confirmed.
 *
 * THE BUG:
 *   - one Iterator handed to several worker threads as a work source
 *
 * THE FIX:
 *   - a real concurrent queue: ConcurrentLinkedQueue.poll() is one atomic
 *     operation returning the element or null. Or confine the cursor: build
 *     it and drain it on one thread.
 *
 * Making the *collection* concurrent does not make its iterator shareable.
 * A CopyOnWriteArrayList iterator is a snapshot and a ConcurrentLinkedQueue
 * iterator is weakly consistent — both are safe to create from any thread
 * and neither is safe to share, which is what the detector reports on.
 */
class WorkQueueServiceTest {

    private SharedIteratorDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedIteratorDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: a cursor per thread. Nothing shared, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void iteratorPerThread_isClean() throws Exception {
        List<String> work = List.of("a", "b", "c");

        Runnable worker = () -> {
            Iterator<String> local = work.iterator();      // this thread's own cursor
            detector.recordAccess(local, "hasNext");
            detector.recordAccess(local, "next");
            while (local.hasNext()) {
                local.next();
            }
        };
        Thread a = new Thread(worker, "worker-a");
        Thread b = new Thread(worker, "worker-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: one cursor, two threads — flagged.
    // -----------------------------------------------------------------------

    @Test
    void sharedIteratorAcrossThreads_isDetected() throws Exception {
        Iterator<String> shared = List.of("a", "b", "c", "d").iterator();

        Runnable worker = () -> {
            detector.recordAccess(shared, "hasNext");
            detector.recordAccess(shared, "next");
        };
        Thread a = new Thread(worker, "worker-a");
        Thread b = new Thread(worker, "worker-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-iterator violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("Iterator"), violation);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("worker-a"), violation);
        assertTrue(violation.contains("worker-b"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: a concurrent collection does not make its iterator shareable.
    // -----------------------------------------------------------------------

    @Test
    void iteratorOfConcurrentCollection_isStillFlaggedWhenShared() throws Exception {
        var concurrent = new CopyOnWriteArrayList<>(List.of("a", "b"));
        Iterator<String> shared = concurrent.iterator();     // snapshot, still one cursor

        Runnable worker = () -> detector.recordAccess(shared, "next");
        Thread a = new Thread(worker, "cow-a");
        Thread b = new Thread(worker, "cow-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "The collection's thread-safety says nothing about the cursor:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the two failure modes themselves — a lost element, and the
    // NoSuchElementException from a next() whose hasNext() was true.
    // -----------------------------------------------------------------------

    @Test
    void sharedCursor_dropsElementsAcrossConsumers() {
        var service = new WorkQueueService(List.of("job-1", "job-2", "job-3", "job-4"));

        // Two consumers draining the ONE shared cursor: between them they see each item
        // once, so neither sees the whole list — the elements are split, not shared.
        List<String> consumerOne = new ArrayList<>();
        List<String> consumerTwo = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            (i % 2 == 0 ? consumerOne : consumerTwo).add(service.takeNext());
        }

        assertEquals(2, consumerOne.size());
        assertEquals(2, consumerTwo.size());
        assertTrue(Collections.disjoint(consumerOne, consumerTwo),
                "each item went to exactly one consumer — a work queue, by accident, and "
                        + "only because this ran on one thread");
    }

    @Test
    void sharedCursor_throwsWhenTheLastElementIsTakenInTheGap() {
        Iterator<String> shared = List.of("only").iterator();

        // Consumer A checks...
        assertTrue(shared.hasNext());
        // ...consumer B takes the element in the gap...
        assertEquals("only", shared.next());
        // ...and consumer A's next() finds nothing, having just been told there was.
        assertThrows(NoSuchElementException.class, shared::next);
    }

    // -----------------------------------------------------------------------
    // Part 5: the fix works — poll() is atomic, so nothing is lost or doubled.
    // -----------------------------------------------------------------------

    @Test
    void concurrentQueue_handsEachItemToExactlyOneWorker() throws Exception {
        var service = new WorkQueueService(List.of("job-1", "job-2", "job-3", "job-4"));
        var taken = Collections.synchronizedList(new ArrayList<String>());

        Runnable worker = () -> {
            String job;
            while ((job = service.takeNextSafely()) != null) {
                taken.add(job);
            }
        };
        Thread a = new Thread(worker, "safe-a");
        Thread b = new Thread(worker, "safe-b");
        a.start();
        b.start();
        a.join();
        b.join();

        assertEquals(4, taken.size(), "no item lost, no item handed out twice");
        assertEquals(4, Set.copyOf(taken).size());
        assertEquals(0, service.remainingInSafeQueue());
    }
}
