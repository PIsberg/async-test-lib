package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SynchronizerMonitorTest {

    @Test
    void noSynchronizersReturnNoIssues() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertFalse(report.hasIssues());
    }

    @Test
    void exactPartiesArriveAndAdvanceNoIssues() throws InterruptedException {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 2);
        Thread t1 = new Thread(() -> {
            monitor.recordBarrierArrival(barrier);
            monitor.recordBarrierAdvance(barrier);
        });
        Thread t2 = new Thread(() -> {
            monitor.recordBarrierArrival(barrier);
            monitor.recordBarrierAdvance(barrier);
        });
        t1.start(); t1.join();
        t2.start(); t2.join();
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertFalse(report.hasIssues());
    }

    @Test
    void fewerThanExpectedArrivalsDetected() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 3);
        monitor.recordBarrierArrival(barrier);
        monitor.recordBarrierArrival(barrier);
        // Only 2 out of 3 expected parties arrived
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertFalse(report.incompleteBarriers.isEmpty());
    }

    @Test
    void duplicateArrivalFromSameThreadDetected() throws InterruptedException {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 2);

        // Same thread arrives twice on the same barrier
        monitor.recordBarrierArrival(barrier);
        monitor.recordBarrierArrival(barrier);

        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        // Either duplicate arrivals flagged or incomplete (implementation may handle either way)
        assertTrue(report.hasIssues() ||
                !report.duplicateArrivals.isEmpty() ||
                !report.incompleteBarriers.isEmpty());
    }

    @Test
    void reportToStringWithIssues() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 5);
        monitor.recordBarrierArrival(barrier); // only 1 of 5
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertFalse(report.hasIssues());
        assertTrue(report.incompleteBarriers.isEmpty());
        assertTrue(report.duplicateArrivals.isEmpty());
    }

    @Test
    void resetClearsState() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 3);
        monitor.recordBarrierArrival(barrier);
        assertTrue(monitor.analyzeSynchronizers().hasIssues());
        monitor.reset();
        assertFalse(monitor.analyzeSynchronizers().hasIssues());
    }

    @Test
    void nullSynchronizerHandled() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        assertDoesNotThrow(() -> monitor.recordBarrierArrival(null));
        assertDoesNotThrow(() -> monitor.recordBarrierAdvance(null));
        assertDoesNotThrow(() -> monitor.recordBarrierReset(null));
    }

    @Test
    void analyze_delegatesToAnalyzeSynchronizers() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 2);
        monitor.recordBarrierArrival(barrier);

        SynchronizerMonitor.SynchronizerReport viaAnalyze = monitor.analyze();
        SynchronizerMonitor.SynchronizerReport viaAnalyzeSynchronizers = monitor.analyzeSynchronizers();

        assertEquals(viaAnalyzeSynchronizers.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeSynchronizers.toString(), viaAnalyze.toString());
    }

    @Test
    void nullSynchronizerIsIgnoredOnRegistration() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        assertDoesNotThrow(() -> monitor.registerSynchronizer(null, 2));
    }

    @Test
    void aBarrierReusedForASecondGenerationIsNotADuplicateArrival() throws InterruptedException {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 2);
        // The same two threads meet at the barrier twice, as pool workers do across rounds.
        java.util.concurrent.ExecutorService peer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int generation = 0; generation < 2; generation++) {
                monitor.recordBarrierArrival(barrier);                           // this thread
                peer.submit(() -> monitor.recordBarrierArrival(barrier)).get();  // the same peer thread
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(e);
        } finally {
            peer.shutdownNow();
        }
        SynchronizerMonitor.SynchronizerReport report = monitor.analyzeSynchronizers();
        assertTrue(report.duplicateArrivals.isEmpty(),
            "four arrivals by two threads on a 2-party barrier are two complete generations, not "
                + "a thread arriving twice: " + report);
        assertFalse(report.hasIssues(), report.toString());
    }

    @Test
    void theSameThreadArrivingTwiceInOneGenerationIsADuplicate() {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        Object barrier = new Object();
        monitor.registerSynchronizer(barrier, 3);
        monitor.recordBarrierArrival(barrier);
        monitor.recordBarrierArrival(barrier);
        assertFalse(monitor.analyzeSynchronizers().duplicateArrivals.isEmpty(),
            "two arrivals by one thread before the barrier trips is the real defect");
    }
}
