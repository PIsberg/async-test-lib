package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for DeadlockDetector.
 *
 * <p>Ordered: the pristine-JVM assertions run first because the baseline tests at the
 * end intentionally leak permanently deadlocked (daemon) threads. In a shared JVM
 * (pitest runs all test classes in one process) another class may have leaked a
 * deadlock already — JVM-wide assertions are therefore assumption-guarded.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeadlockDetectorTest {

    @Test
    @Order(1)
    void noDeadlockReturnsNoIssues() {
        // Baselining at construction excludes deadlocks leaked by earlier tests,
        // so this holds even in a contaminated shared JVM.
        DeadlockDetector detector = new DeadlockDetector();

        DeadlockDetector.DeadlockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "No new threads deadlocked — report should have no issues");
    }

    @Test
    @Order(2)
    void hasDeadlockReturnsFalseWithNoDeadlock() {
        // hasDeadlock() is JVM-wide by design; only meaningful in a pristine JVM.
        assumeTrue(ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null,
                "JVM already has deadlocked threads (shared-JVM run) — skipping JVM-wide assertion");

        boolean result = DeadlockDetector.hasDeadlock();

        assertFalse(result, "hasDeadlock() should return false when no threads are deadlocked");
    }

    @Test
    void deadlockReportHasIssuesFalseByDefault() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(false);

        assertFalse(report.hasIssues(), "Report constructed with false should report no issues");
    }

    @Test
    void deadlockReportHasIssuesTrueWhenDeadlocked() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(true);

        assertTrue(report.hasIssues(), "Report constructed with true should report issues");
    }

    @Test
    void reportToStringContainsDeadlockInfo() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(true);

        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("DEADLOCK"), "toString() should mention DEADLOCK when deadlocked");
    }

    @Test
    void reportToStringCleanWhenNoIssue() {
        DeadlockDetector.DeadlockReport report = new DeadlockDetector.DeadlockReport(false);

        String text = report.toString();

        assertNotNull(text);
        assertEquals("No deadlocks detected.", text,
                "toString() should return clean message when no deadlock");
    }

    @Test
    void getLockContentionSummaryNotNull() {
        String summary = DeadlockDetector.getLockContentionSummary();

        assertNotNull(summary, "getLockContentionSummary() must not return null");
        assertFalse(summary.isBlank(), "getLockContentionSummary() must not return a blank string");
    }

    // ---- Baseline semantics: only deadlocks formed after construction are reported ----

    @Test
    @Order(10)
    void analyzeReportsDeadlockCreatedAfterConstruction() throws InterruptedException {
        DeadlockDetector detector = new DeadlockDetector();

        createLeakedDeadlock();

        assertTrue(detector.analyze().hasIssues(),
                "A deadlock formed after construction must be reported");
    }

    @Test
    @Order(11)
    void analyzeExcludesDeadlocksPresentAtConstruction() throws InterruptedException {
        if (!DeadlockDetector.hasDeadlock()) {
            createLeakedDeadlock();
        }

        DeadlockDetector detector = new DeadlockDetector();

        assertFalse(detector.analyze().hasIssues(),
                "Deadlocks that predate the detector must be excluded from analyze()");
    }

    /**
     * Parks two daemon threads in a classic lock-ordering deadlock and returns once the
     * JVM's ThreadMXBean reports both as deadlocked. The threads are leaked for the JVM's
     * lifetime — exactly the contamination scenario the baseline logic exists for.
     */
    private static void createLeakedDeadlock() throws InterruptedException {
        Object lockA = new Object();
        Object lockB = new Object();
        CountDownLatch bothHold = new CountDownLatch(2);
        Runnable grabAThenB = () -> {
            synchronized (lockA) {
                bothHold.countDown();
                awaitQuietly(bothHold);
                synchronized (lockB) { /* never reached */ }
            }
        };
        Runnable grabBThenA = () -> {
            synchronized (lockB) {
                bothHold.countDown();
                awaitQuietly(bothHold);
                synchronized (lockA) { /* never reached */ }
            }
        };
        Thread t1 = new Thread(grabAThenB, "deadlock-detector-test-leak-1");
        Thread t2 = new Thread(grabBThenA, "deadlock-detector-test-leak-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            long[] ids = mx.findDeadlockedThreads();
            if (ids != null && contains(ids, t1.threadId()) && contains(ids, t2.threadId())) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Deadlock did not register with ThreadMXBean within 10 seconds");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean contains(long[] ids, long id) {
        for (long candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }
}
