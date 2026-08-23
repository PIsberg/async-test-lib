package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SharedCollectionDetector}.
 */
public class SharedCollectionDetectorTest {

    @Test
    void testSingleThreadReadNoIssues() {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "safe-list", "ArrayList");
        detector.recordRead(list, "safe-list", "get");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread read should not report issues");
    }

    @Test
    void testConcurrentWriteDetection() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "shared-list", "ArrayList");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                detector.recordWrite(list, "shared-list", "add");
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                detector.recordWrite(list, "shared-list", "add");
            }
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Concurrent writes should be detected");
        assertFalse(report.concurrentWriteViolations.isEmpty(), "Should report write violations");
        assertTrue(report.concurrentWriteViolations.get(0).contains("own monitor count as guarded"));
    }

    @Test
    void testMixedReadWriteFromMultipleThreads() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        HashMap<String, String> map = new HashMap<>();

        detector.registerCollection(map, "shared-map", "HashMap");

        // One writer thread
        Thread writer = new Thread(() -> detector.recordWrite(map, "shared-map", "put"));

        // Multiple reader threads
        Thread r1 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));
        Thread r2 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));
        Thread r3 = new Thread(() -> detector.recordRead(map, "shared-map", "get"));

        writer.start(); writer.join();
        r1.start(); r2.start(); r3.start();
        r1.join(); r2.join(); r3.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Mixed read-write with multiple readers should be detected");
    }

    @Test
    void testAutoRegistrationOnFirstAccess() {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        // Write without prior registration
        detector.recordWrite(list, "auto-list", "add");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertNotNull(report);
        assertTrue(report.collectionActivity.containsKey("auto-list"), "Should auto-register on first access");
    }

    @Test
    void testNullSafety() {
        SharedCollectionDetector detector = new SharedCollectionDetector();

        assertDoesNotThrow(() -> {
            detector.registerCollection(null, "null-col", "ArrayList");
            detector.recordRead(null, "null", "get");
            detector.recordWrite(null, "null", "add");
        });

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> list = new ArrayList<>();

        detector.registerCollection(list, "report-list", "ArrayList");

        Thread t1 = new Thread(() -> detector.recordWrite(list, "report-list", "add"));
        Thread t2 = new Thread(() -> detector.recordWrite(list, "report-list", "add"));

        t1.start(); t2.start();
        t1.join();  t2.join();

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("SHARED COLLECTION ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Concurrent Write Violations"), "Should describe violation type");
        assertTrue(text.contains("ConcurrentHashMap"), "Should suggest fix");
    }

    @Test
    void testMultipleCollectionsTrackedIndependently() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<String> sharedList = new ArrayList<>();
        List<String> safeList   = new ArrayList<>();

        detector.registerCollection(sharedList, "shared", "ArrayList");
        detector.registerCollection(safeList, "safe", "ArrayList");

        Thread t1 = new Thread(() -> detector.recordWrite(sharedList, "shared", "add"));
        Thread t2 = new Thread(() -> detector.recordWrite(sharedList, "shared", "add"));
        t1.start(); t2.start();
        t1.join();  t2.join();

        // safeList used by one thread only
        detector.recordWrite(safeList, "safe", "add");

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertTrue(report.hasIssues(), "shared list should trigger issues");

        boolean safeListFlagged = report.concurrentWriteViolations.stream()
                .anyMatch(s -> s.contains("safe"));
        assertFalse(safeListFlagged, "safe list should not be flagged");
    }

    @Test
    void oneWriterPerRoundIsSequentialNotConcurrent() throws InterruptedException {
        // The harness orders rounds, so a collection written by one thread in each of three
        // rounds was written sequentially. Counting thread ids across the run made that three
        // writers and a race, and with virtual threads (one per task) every round brought new ids.
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<Object> list = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            detector.markInvocationStart();
            Thread writer = new Thread(() -> detector.recordWrite(list, "round-list", "add"));
            writer.start();
            writer.join();
        }

        assertFalse(detector.analyze().hasIssues(),
                "one writer per round never had two writers in flight, so nothing raced");
    }

    @Test
    void findingReportsTheWidestRoundNotTheAccumulatedIds() throws InterruptedException {
        SharedCollectionDetector detector = new SharedCollectionDetector();
        List<Object> list = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            detector.markInvocationStart();
            Thread first = new Thread(() -> detector.recordWrite(list, "round-list", "add"));
            Thread second = new Thread(() -> detector.recordWrite(list, "round-list", "add"));
            first.start();
            second.start();
            first.join();
            second.join();
        }

        SharedCollectionDetector.SharedCollectionReport report = detector.analyze();
        assertTrue(report.hasIssues(), "two writers in the same round is the race this detector exists for");
        assertTrue(report.concurrentWriteViolations.get(0).contains("from 2 threads (writes: 6)"),
                "the finding names the widest round, two threads, not the six ids the run saw: "
                        + report.concurrentWriteViolations.get(0));
    }

    @Test
    void readersAndWritersAreCountedWithinARound() throws InterruptedException {
        // One writer and two readers in the same round is the visibility shape; the same three
        // threads spread over three rounds are not.
        SharedCollectionDetector sameRound = new SharedCollectionDetector();
        HashMap<Object, Object> map = new HashMap<>();
        sameRound.markInvocationStart();
        Thread writer = new Thread(() -> sameRound.recordWrite(map, "round-map", "put"));
        Thread reader1 = new Thread(() -> sameRound.recordRead(map, "round-map", "get"));
        Thread reader2 = new Thread(() -> sameRound.recordRead(map, "round-map", "get"));
        writer.start();
        reader1.start();
        reader2.start();
        writer.join();
        reader1.join();
        reader2.join();
        assertTrue(sameRound.analyze().hasIssues(), "one writer and two readers in one round");

        SharedCollectionDetector spread = new SharedCollectionDetector();
        spread.markInvocationStart();
        Thread w = new Thread(() -> spread.recordWrite(map, "round-map", "put"));
        w.start();
        w.join();
        spread.markInvocationStart();
        Thread r1 = new Thread(() -> spread.recordRead(map, "round-map", "get"));
        r1.start();
        r1.join();
        spread.markInvocationStart();
        Thread r2 = new Thread(() -> spread.recordRead(map, "round-map", "get"));
        r2.start();
        r2.join();
        assertFalse(spread.analyze().hasIssues(), "the same accesses in three ordered rounds never overlapped");
    }
}
