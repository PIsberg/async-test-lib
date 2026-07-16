package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SleepInLockDetectorTest {

    private SleepInLockDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SleepInLockDetector();
    }

    @Test
    void noIssues_whenNoSleepInLock() {
        // Don't call recordSleep() - just verify clean report
        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void disabledDetector_returnsNoIssues() {
        detector.disable();
        detector.startMonitoring();
        detector.recordSleep(100);
        detector.stopMonitoring();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void clear_removesAllEvents() {
        detector.startMonitoring();
        detector.recordSleep(50);
        detector.clear();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void report_containsSummary() {
        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("SleepInLockReport"));
    }

    @Test
    void report_showsNoIssues_whenClean() {
        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        String reportStr = report.toString();
        assertTrue(reportStr.contains("No sleep-in-lock patterns detected"));
    }

    // Method name deliberately avoids the substrings "lock"/"Lock" so the assertion
    // cannot be satisfied by name-matching heuristics on the stack trace — only a
    // real held-monitor check can make this pass.
    @Test
    void detectsSleepWhileHoldingMonitor() {
        detector.startMonitoring();
        Object monitor = new Object();
        synchronized (monitor) {
            detector.recordSleep(100);
        }

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "sleeping inside a synchronized block is the detector's headline pattern");
        assertEquals("synchronized", report.getEvents().get(0).lockType);
    }

    @Test
    void detectsSleepWhileHoldingJucSynchronizer() {
        detector.startMonitoring();
        java.util.concurrent.locks.ReentrantLock juc = new java.util.concurrent.locks.ReentrantLock();
        juc.lock();
        try {
            detector.recordSleep(100);
        } finally {
            juc.unlock();
        }

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), "sleeping while holding a ReentrantLock must be detected");
        assertEquals("ReentrantLock", report.getEvents().get(0).lockType);
    }

    @Test
    void noEvent_whenNoLockHeld_evenFromLockNamedMethod() {
        detector.startMonitoring();
        recordSleepFromTryLockHelper();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "no lock is held — a caller method whose name contains 'Lock' must not be flagged");
    }

    // The name contains "Lock" on purpose: it must NOT be mistaken for a held lock.
    private void recordSleepFromTryLockHelper() {
        detector.recordSleep(100);
    }

    // ConcurrencyRunner executes test bodies on ThreadPoolExecutor workers, and a
    // running Worker holds its own AQS while executing a task — that internal
    // synchronizer must not be mistaken for a user-held lock.
    @Test
    void noEvent_whenSleepingInThreadPoolWorkerWithoutUserLock() throws Exception {
        detector.startMonitoring();
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> detector.recordSleep(100)).get();
        } finally {
            pool.shutdownNow();
        }

        assertFalse(detector.analyze().hasIssues(),
            "the executor's internal Worker synchronizer is not a user lock");
    }

    @Test
    void detectsSleepInSynchronizedBlockInsideThreadPoolWorker() throws Exception {
        detector.startMonitoring();
        Object monitor = new Object();
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> {
                synchronized (monitor) {
                    detector.recordSleep(100);
                }
            }).get();
        } finally {
            pool.shutdownNow();
        }

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertTrue(report.hasIssues(), "a real user monitor held inside a pool task must be detected");
        assertEquals("synchronized", report.getEvents().get(0).lockType);
    }
}
