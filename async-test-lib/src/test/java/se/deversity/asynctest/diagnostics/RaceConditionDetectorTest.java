package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
