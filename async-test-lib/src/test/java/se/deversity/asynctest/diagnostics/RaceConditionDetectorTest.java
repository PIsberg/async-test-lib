package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RaceConditionDetector.
 */
public class RaceConditionDetectorTest {

    // ---- Analysis concurrent with recording: the runner's timeout path ----
    //
    // When a round times out, ConcurrencyRunner cancels the workers and reports; a
    // cancelled worker can still be unwinding (recording accesses) while the runner
    // thread analyzes. Analysis must tolerate concurrent recordAccess without throwing
    // ConcurrentModificationException — a CME here is contained by DetectorRegistry.ifIssue,
    // which silently costs this detector's entire report on exactly the runs (timeouts)
    // where the diagnosis matters most.
    @Test
    void analyzeWhileRecordingDoesNotThrow() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        // Seed a cross-thread write so analysis enters the per-field scan immediately.
        Thread seeder = new Thread(() -> detector.recordFieldWrite(shared, "hot"));
        seeder.start();
        seeder.join();
        detector.recordFieldWrite(shared, "hot");

        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread writer = new Thread(() -> {
                for (int n = 0; n < 100_000; n++) {
                    detector.recordFieldWrite(shared, "hot");
                }
            });
            writer.start();
            writers.add(writer);
        }
        try {
            assertDoesNotThrow(() -> {
                while (writers.stream().anyMatch(Thread::isAlive)) {
                    detector.analyzeRaceConditions();
                }
            }, "analyzing while recorder threads are still running must not throw");
        } finally {
            for (Thread writer : writers) {
                writer.join();
            }
        }
    }

    // ---- Harness-derived happens-before: rounds are ordered, cross-round pairs cannot race ----
    //
    // ConcurrencyRunner ends a round by awaiting the workers' latch and starts the next by
    // submitting fresh tasks: everything recorded in round N happens-before everything
    // recorded in round N+1, through the runner thread. The runner bumps the detector's
    // invocation epoch at each round start; only same-epoch accesses may be reported.

    @Test
    void crossRoundAccessesOrderedByTheHarnessAreNotFlagged() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        // Round 1: one thread writes.
        detector.markInvocationStart();
        Thread first = new Thread(() -> detector.recordFieldWrite(shared, "handoff"));
        first.start();
        first.join();

        // Round 2: a different thread writes.
        detector.markInvocationStart();
        Thread second = new Thread(() -> detector.recordFieldWrite(shared, "handoff"));
        second.start();
        second.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertFalse(report.hasIssues(),
                "writes in different invocation rounds are ordered by the harness's own "
                        + "latch/submit happens-before edges and must not be reported as a race");
    }

    @Test
    void sameRoundCrossThreadWritesStillFlaggedAfterEpochScoping() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        detector.markInvocationStart();
        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "value"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "value"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "cross-thread writes within one round have no ordering and must still be flagged");
    }

    @Test
    void noRecordingsReturnNoIssues() {
        RaceConditionDetector detector = new RaceConditionDetector();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No recordings — should report no issues");
        assertTrue(report.unsafeAccesses.isEmpty());
        assertTrue(report.potentialRaces.isEmpty());
    }

    @Test
    void singleThreadAccessNoIssues() {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object obj = new Object();

        // Single thread reads and writes the same field
        detector.recordFieldRead(obj, "counter");
        detector.recordFieldWrite(obj, "counter");

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertFalse(report.hasIssues(), "All accesses from one thread — no race possible");
    }

    @Test
    void crossThreadWritesDetected() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "value"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "value"));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertNotNull(report);
        assertFalse(report.potentialRaces.isEmpty(),
                "Two threads writing the same field should produce a potential race entry");
        assertTrue(report.hasIssues());
    }

    @Test
    void readWriteFromDifferentThreadsDetected() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        Thread reader = new Thread(() -> detector.recordFieldRead(shared, "state"));
        Thread writer = new Thread(() -> detector.recordFieldWrite(shared, "state"));

        reader.start();
        reader.join();
        writer.start();
        writer.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertNotNull(report);
        assertTrue(report.hasIssues(),
                "Read from one thread followed by write from another should be flagged as unsafe");
    }

    @Test
    void twoWritesUnderTheSameReadLockAreNotGuarded() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();
        Object readWriteLock = new Object();

        // Both threads write while holding the same lock in shared mode. A read lock keeps
        // writers out of the read side and nothing else: it admits every other reader, so two
        // writers under it are racing. AtomicityValidator has always known that, because it uses
        // the mode-aware fingerprint; this detector folded shared locks in as if exclusive.
        Runnable writeUnderReadLock = () -> {
            HeldLocks.acquired(readWriteLock, true);
            try {
                detector.recordFieldWrite(shared, "state");
            } finally {
                HeldLocks.released(readWriteLock, true);
            }
        };

        Thread first = new Thread(writeUnderReadLock);
        Thread second = new Thread(writeUnderReadLock);
        first.start();
        first.join();
        second.start();
        second.join();

        assertTrue(detector.analyzeRaceConditions().hasIssues(),
            "two writes under the same read lock have no common exclusive lock, so this is a "
                + "write-write race: " + detector.analyzeRaceConditions());
    }

    @Test
    void twoWritesUnderTheSameWriteLockAreGuarded() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();
        Object exclusiveLock = new Object();

        // The twin: the same two writes, under the same lock held exclusively, are correct code.
        Runnable writeUnderWriteLock = () -> {
            HeldLocks.acquired(exclusiveLock, false);
            try {
                detector.recordFieldWrite(shared, "state");
            } finally {
                HeldLocks.released(exclusiveLock, false);
            }
        };

        Thread first = new Thread(writeUnderWriteLock);
        Thread second = new Thread(writeUnderWriteLock);
        first.start();
        first.join();
        second.start();
        second.join();

        assertFalse(detector.analyzeRaceConditions().hasIssues(),
            "one exclusive lock held across both writes is what guarding means: "
                + detector.analyzeRaceConditions());
    }

    @Test
    void nullInputHandledGracefully() {
        RaceConditionDetector detector = new RaceConditionDetector();

        // null object — should not throw
        assertDoesNotThrow(() -> detector.recordFieldRead(null, "field"));
        assertDoesNotThrow(() -> detector.recordFieldWrite(null, "field"));

        // null field name — should not throw
        Object obj = new Object();
        assertDoesNotThrow(() -> detector.recordFieldRead(obj, null));
        assertDoesNotThrow(() -> detector.recordFieldWrite(obj, null));
    }

    @Test
    void disabledDetectorSkipsRecording() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        detector.disable();

        Object shared = new Object();
        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "x"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "x"));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();

        assertFalse(report.hasIssues(), "Disabled detector must not record any accesses");
    }

    @Test
    void reportToStringContainsRaceInfo() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "data"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "data"));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("race") || text.contains("RACE") || text.contains("write"),
                "toString() for a race-condition report should describe the detected races");
    }

    @Test
    void resetClearsState() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();

        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "n"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "n"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        detector.reset();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertFalse(report.hasIssues(), "After reset() all recorded accesses must be cleared");
    }

    // ---- Ordering the lockset cannot see: the shared happens-before model ----
    //
    // Each case is correct code the lockset alone reported, followed by the twin that differs by
    // the one step that breaks the ordering. The edges are declared through HappensBefore by hand
    // here, which is what a recording-fed test does; with the agent attached the same edges come
    // from the woven calls.

    /** A plain holder, so the volatile rule can see the declared fields. */
    static final class Box {
        int f;
    }

    /** One plain field published by one volatile flag. */
    static final class Pub {
        int data;
        volatile boolean ready;
    }

    /** The same holder with the flag not volatile, which publishes nothing. */
    static final class PlainPub {
        int data;
        boolean ready;
    }

    @Test
    void anObjectHandedOffThroughAQueueIsNotARace() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        BlockingQueue<Box> queue = new LinkedBlockingQueue<>();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            box.f = 1;
            detector.recordFieldWrite(box, "f");
            HappensBefore.release(box); // what BlockingQueue.put publishes
            queue.add(box);
        });
        Thread consumer = new Thread(() -> {
            try {
                Box taken = queue.take();
                HappensBefore.acquire(taken); // what a successful take receives
                detector.recordFieldRead(taken, "f");
                taken.f++;
                detector.recordFieldWrite(taken, "f");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();

        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "every access of the consumer follows the producer's hand-off: "
                        + detector.analyzeRaceConditions());
    }

    @Test
    void aProducerThatKeepsWritingAfterTheHandOffStillRaces() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        BlockingQueue<Box> queue = new LinkedBlockingQueue<>();
        Box box = new Box();
        Thread producer = new Thread(() -> {
            HappensBefore.release(box);
            queue.add(box);
            box.f = 2; // after the hand-off: nothing orders this against the consumer
            detector.recordFieldWrite(box, "f");
        });
        Thread consumer = new Thread(() -> {
            try {
                Box taken = queue.take();
                HappensBefore.acquire(taken);
                detector.recordFieldRead(taken, "f");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();

        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "a write after the release is not published by it");
    }

    @Test
    void aVolatileFlagPublishesThePlainFieldWrittenBeforeIt() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Pub pub = new Pub();
        Thread writer = new Thread(() -> {
            pub.data = 42;
            detector.recordFieldWrite(pub, "data");
            // A volatile write is recorded before it is made, a volatile read after: the release
            // then precedes every read that can see the value, whichever thread runs first.
            detector.recordFieldWrite(pub, "ready");
            pub.ready = true;
        });
        Thread reader = new Thread(() -> {
            while (!pub.ready) {
                Thread.onSpinWait();
            }
            detector.recordFieldRead(pub, "ready");
            int seen = pub.data;
            detector.recordFieldRead(pub, "data");
            assertEquals(42, seen);
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertFalse(report.hasIssues(), "the reader saw the volatile write, which publishes "
                + "the plain write before it, and a volatile read is never a data race: " + report);
    }

    @Test
    void aPlainFlagPublishesNothing() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        PlainPub pub = new PlainPub();
        Thread writer = new Thread(() -> {
            pub.data = 42;
            detector.recordFieldWrite(pub, "data");
            pub.ready = true;
            detector.recordFieldWrite(pub, "ready");
        });
        Thread reader = new Thread(() -> {
            detector.recordFieldRead(pub, "ready");
            detector.recordFieldRead(pub, "data");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertTrue(report.unsafeAccesses.stream().anyMatch(line -> line.contains(".data:")),
                "without volatile the flag orders nothing and the data field races: " + report);
    }

    @Test
    void twoThreadsWritingOneVolatileFieldStillRace() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Pub pub = new Pub();
        Runnable flip = () -> {
            pub.ready = !pub.ready; // volatile, and still a lost update
            detector.recordFieldWrite(pub, "ready");
        };
        Thread first = new Thread(flip);
        Thread second = new Thread(flip);
        first.start();
        second.start();
        first.join();
        second.join();

        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "volatile orders reads, not two writers: this is the lost update the detector exists for");
    }

    @Test
    void aChildOrderedByStartAndJoinIsNotARace() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        box.f = 1;
        detector.recordFieldWrite(box, "f");
        Thread child = new Thread(() -> {
            box.f++;
            detector.recordFieldRead(box, "f");
            detector.recordFieldWrite(box, "f");
        });
        HappensBefore.fork(child);
        child.start();
        child.join();
        HappensBefore.join(child);
        detector.recordFieldRead(box, "f");

        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "Thread.start orders the parent's write before the child and join orders the "
                        + "child before the parent's read: " + detector.analyzeRaceConditions());
    }

    @Test
    void aParentWritingWhileTheChildRunsStillRaces() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        CountDownLatch childWrote = new CountDownLatch(1);
        Thread child = new Thread(() -> {
            detector.recordFieldWrite(box, "f");
            childWrote.countDown();
        });
        HappensBefore.fork(child);
        child.start();
        childWrote.await();
        detector.recordFieldWrite(box, "f"); // between start and join
        child.join();
        HappensBefore.join(child);

        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "a write between start and join is ordered against nothing the child did");
    }

    @Test
    void theHotspotLineCountsWriterThreadsNotWrites() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();
        Runnable twoWrites = () -> {
            detector.recordFieldWrite(shared, "hits");
            detector.recordFieldWrite(shared, "hits");
        };
        Thread first = new Thread(twoWrites);
        Thread second = new Thread(twoWrites);
        Thread reader = new Thread(() -> detector.recordFieldRead(shared, "hits"));
        first.start();
        second.start();
        reader.start();
        first.join();
        second.join();
        reader.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertEquals(1, report.potentialRaces.size(), report.toString());
        String line = report.potentialRaces.iterator().next();
        assertTrue(line.contains("written by 2 threads"),
                "two threads wrote, the reader did not: " + line);
        assertTrue(line.contains("4 writes"), "the write count is still stated: " + line);
        assertFalse(line.contains("-1"), "an unknown line must not be printed as -1: " + line);
    }

    @Test
    void oneWriterAndAReaderIsASequenceNotAWriteHotspot() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();
        Thread writer = new Thread(() -> {
            detector.recordFieldWrite(shared, "state");
            detector.recordFieldWrite(shared, "state");
        });
        Thread reader = new Thread(() -> detector.recordFieldRead(shared, "state"));
        writer.start();
        writer.join();
        reader.start();
        reader.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertTrue(report.potentialRaces.isEmpty(),
                "one writer cannot be a concurrent-write hotspot: " + report);
        assertFalse(report.unsafeAccesses.isEmpty(), "the unordered read still is: " + report);
    }

    @Test
    void theHotspotNamesWhereTheFirstWriteWasRecorded() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object shared = new Object();
        Thread t1 = new Thread(() -> detector.recordFieldWrite(shared, "value"));
        Thread t2 = new Thread(() -> detector.recordFieldWrite(shared, "value"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        String line = detector.analyzeRaceConditions().potentialRaces.iterator().next();
        assertTrue(line.contains("RaceConditionDetectorTest.java:"),
                "the first write's site is captured once per field and printed: " + line);
    }

    @Test
    void analyze_delegatesToAnalyzeRaceConditions() {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object obj = new Object();
        detector.recordFieldWrite(obj, "field");
        detector.recordFieldWrite(obj, "field");

        RaceConditionDetector.RaceConditionReport viaAnalyze = detector.analyze();
        RaceConditionDetector.RaceConditionReport viaAnalyzeRaceConditions = detector.analyzeRaceConditions();

        assertEquals(viaAnalyzeRaceConditions.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeRaceConditions.toString(), viaAnalyze.toString());
    }
}
