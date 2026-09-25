package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NestedMonitorLockoutDetectorTest {

    @Test
    void testNoIssuesWithNoBlockingCalls() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lock = new Object();
        detector.recordMonitorAcquired(lock);
        detector.recordMonitorReleased(lock);

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenBlockingWithoutMonitor() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        // Blocking op, but no monitor held
        detector.recordBlockingOperationAttempted("future.get()");

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testDetectsBlockingWhileHoldingMonitor() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lock = new Object();
        detector.recordMonitorAcquired(lock);
        detector.recordBlockingOperationAttempted("Future.get()");
        detector.recordMonitorReleased(lock);

        NestedMonitorLockoutDetector.NestedMonitorLockoutReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect blocking op while monitor held");
        assertFalse(report.incidents.isEmpty());
        assertTrue(report.incidents.get(0).contains("Future.get()"));
        assertTrue(report.incidents.get(0).contains("1 monitor"));
    }

    /**
     * The canonical guarded wait. wait() releases the monitor it is called on, and that is the
     * only monitor held, so no thread is locked out of anything: the notifier can get in.
     */
    @Test
    void theCanonicalWaitLoopOnItsOwnMonitorIsSilent() throws Exception {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        Thread notifier = new Thread(() -> {
            synchronized (monitor) {
                ready[0] = true;
                monitor.notifyAll();
            }
        });
        synchronized (monitor) {
            detector.recordMonitorAcquired(monitor);
            notifier.start();
            while (!ready[0]) {
                detector.recordBlockingOperationAttempted("wait()");
                monitor.wait(1_000);
            }
            detector.recordMonitorReleased(monitor);
        }
        notifier.join();

        assertFalse(detector.analyze().hasIssues(),
                "synchronized (m) { while (!ready) m.wait(); } releases m while it waits, so the "
                        + "notifier got in and the loop ended: " + detector.analyze());
    }

    @Test
    void conditionAwaitIsNotObjectWaitAndReleasesNoMonitor() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object monitor = new Object();
        detector.recordMonitorAcquired(monitor);
        detector.recordBlockingOperationAttempted("condition.await()");
        detector.recordMonitorReleased(monitor);

        assertTrue(detector.analyze().hasIssues(),
                "Condition.await() releases its Lock, never a monitor, so the monitor stays held");
    }

    @Test
    void testDetectsWaitWhileHoldingMultipleMonitors() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lockA = new Object();
        Object lockB = new Object();
        detector.recordMonitorAcquired(lockA);
        detector.recordMonitorAcquired(lockB);
        detector.recordBlockingOperationAttempted("obj.wait()");
        detector.recordMonitorReleased(lockB);
        detector.recordMonitorReleased(lockA);

        NestedMonitorLockoutDetector.NestedMonitorLockoutReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.incidents.get(0).contains("1 monitor(s) besides the one it waits on"),
                report.incidents.get(0));
    }

    @Test
    void recordWaitAttemptedOnTheOnlyHeldMonitorIsSilent() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object monitor = new Object();
        detector.recordMonitorAcquired(monitor);
        detector.recordWaitAttempted(monitor);
        detector.recordMonitorReleased(monitor);

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void recordWaitAttemptedOnTheInnerMonitorWhileHoldingTheOuterFires() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object outer = new Object();
        Object inner = new Object();
        detector.recordMonitorAcquired(outer);
        detector.recordMonitorAcquired(inner);
        detector.recordWaitAttempted(inner);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "outer stays held while the thread waits on inner");
        assertTrue(report.incidents.get(0).contains("1 monitor(s) besides"), report.incidents.get(0));
    }

    @Test
    void testReleaseRemovesMonitor() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lock = new Object();
        detector.recordMonitorAcquired(lock);
        detector.recordMonitorReleased(lock);
        // After release, blocking op should not be flagged
        detector.recordBlockingOperationAttempted("lock.lock()");

        assertFalse(detector.analyze().hasIssues(), "Released monitor should not trigger issue");
    }

    @Test
    void testNullSafety() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        assertDoesNotThrow(() -> {
            detector.recordMonitorAcquired(null);
            detector.recordMonitorReleased(null);
            detector.recordBlockingOperationAttempted(null);
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportDeduplicatesSameMessage() throws InterruptedException {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lock = new Object();

        // Simulate the same thread hitting the same issue twice
        detector.recordMonitorAcquired(lock);
        detector.recordBlockingOperationAttempted("future.get()");
        detector.recordBlockingOperationAttempted("future.get()");
        detector.recordMonitorReleased(lock);

        NestedMonitorLockoutDetector.NestedMonitorLockoutReport report = detector.analyze();
        assertTrue(report.hasIssues());
        // toString deduplicates via LinkedHashSet
        String str = report.toString();
        int firstIdx = str.indexOf("future.get()");
        int lastIdx  = str.lastIndexOf("future.get()");
        assertEquals(firstIdx, lastIdx, "Duplicate messages should be deduplicated in output");
    }

    @Test
    void testReportToStringContainsFixHint() {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object lock = new Object();
        detector.recordMonitorAcquired(lock);
        detector.recordBlockingOperationAttempted("join()");

        String str = detector.analyze().toString();
        assertTrue(str.contains("NESTED MONITOR LOCKOUT ISSUES DETECTED"));
        assertTrue(str.contains("Fix"));
    }

    @Test
    void testMultipleThreadsIndependent() throws InterruptedException {
        NestedMonitorLockoutDetector detector = new NestedMonitorLockoutDetector();
        Object sharedLock = new Object();

        Thread t1 = new Thread(() -> {
            detector.recordMonitorAcquired(sharedLock);
            detector.recordBlockingOperationAttempted("t1-blocking");
            detector.recordMonitorReleased(sharedLock);
        });

        Thread t2 = new Thread(() -> {
            // t2 does not hold any monitor before blocking
            detector.recordBlockingOperationAttempted("t2-blocking");
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        NestedMonitorLockoutDetector.NestedMonitorLockoutReport report = detector.analyze();
        assertTrue(report.hasIssues(), "t1's incident should be captured");
        assertTrue(report.incidents.stream().anyMatch(i -> i.contains("t1-blocking")));
        assertFalse(report.incidents.stream().anyMatch(i -> i.contains("t2-blocking")),
                "t2 had no monitor held so should not be flagged");
    }
}
