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
    void noEvent_whenNoLockHeld_evenFromLockNamedMethod() throws Exception {
        detector.startMonitoring();
        // On a thread this test owns, so "no lock is held" is a fact about the recording and
        // not about whatever the harness happened to be holding. recordSleep(long) asks the JVM
        // which monitors the CALLING thread holds, and under surefire that is a thread whose
        // whole stack is this test - but under pitest's coverage phase the same code runs on a
        // thread pitest drives, and a monitor taken by the framework around the call was read as
        // a lock the caller held. The test then failed as "did not pass without mutation" and
        // aborted the run, which is one of the two things keeping the weekly gate from ever
        // producing a score (#479).
        Thread caller = new Thread(this::recordSleepFromTryLockHelper, "no-lock-caller");
        caller.start();
        caller.join();

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "no lock is held — a caller method whose name contains 'Lock' must not be flagged: "
                + report);
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
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("KNOWN BLIND SPOT: a virtual thread holding a monitor is not seen")
    void sleepingInsideSynchronizedOnAVirtualThreadIsNotDetected() throws Exception {
        SleepInLockDetector virtualDetector = new SleepInLockDetector();
        virtualDetector.startMonitoring();
        Object monitor = new Object();

        Thread vt = Thread.ofVirtual().unstarted(() -> {
            synchronized (monitor) {
                virtualDetector.recordSleep(100);
            }
        });
        vt.start();
        vt.join();

        // Pinned as a limitation rather than assumed away, in the DetectionCoverageTest
        // tradition. recordSleep establishes whether a lock is held by asking ThreadMXBean,
        // and ThreadMXBean.getThreadInfo(long[]) returns null for a virtual thread - so the
        // monitor is invisible and no finding is produced.
        //
        // This matters more than it looks: @AsyncTest runs on virtual threads by default, so
        // in the library's own default configuration this detector cannot fire at all. The
        // consumer fixture therefore pins its firing direction with useVirtualThreads = false
        // and says why.
        //
        // If this ever starts reporting, the blind spot is gone: flip the assertion, drop the
        // useVirtualThreads = false from the fixture, and say so in the changelog.
        assertFalse(virtualDetector.analyze().hasIssues(),
            "If a virtual thread's monitors became visible to ThreadMXBean, this detector would "
            + "work under the library's default configuration and this limitation should be "
            + "retired rather than left pinned.");
    }

}
