package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReentrantLockDetector.
 */
public class ReentrantLockDetectorTest {

    @Test
    void testNormalLockUsage() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        detector.registerLock(lock, "normalLock");
        detector.recordLockAcquired(lock, "Thread-1");
        detector.recordLockReleased(lock, "Thread-1");

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testLockTimeoutAloneIsContextNotAFinding() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        detector.registerLock(lock, "timeoutLock");
        detector.recordLockTimeout(lock);  // tryLock timed out, and the lock is free now

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(),
                "a timeout the caller handled is correct code; discarding the false return is "
                        + "TRY_LOCK_MISUSE's finding (#589)");
        assertTrue(report.toString().contains("timeoutLock"), "the timeout is still printed as context");
    }

    @Test
    void testStarvationDetection() {
        ReentrantLockDetector detector = new ReentrantLockDetector();

        detector.recordStarvation("Thread-1", 5000);  // Waited 5 seconds
        detector.recordStarvation("Thread-2", 10000); // Waited 10 seconds

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect starvation");
    }

    @Test
    void testMultiThreadLockUsage() throws Exception {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        detector.registerLock(lock, "multiThreadLock");

        Thread t1 = new Thread(() -> {
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "Thread-1");
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "Thread-1");
                lock.unlock();
            }
        });

        Thread t2 = new Thread(() -> {
            lock.lock();
            try {
                detector.recordLockAcquired(lock, "Thread-2");
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "Thread-2");
                lock.unlock();
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-thread usage should work correctly");
    }

    @Test
    void testReportToString() throws Exception {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        detector.registerLock(lock, "testLock");
        Thread leaker = new Thread(lock::lock, "leaker");
        leaker.start();
        leaker.join(10_000);
        detector.recordLockTimeout(lock);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("REENTRANTLOCK ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Lock Still Held At Analysis"), "Report should name the held lock");
        assertTrue(reportStr.contains("leaker"), "Report should name the holder the lock reported: " + reportStr);
        assertTrue(reportStr.contains("tryLock() timeouts"), "Report should keep timeouts as context");
    }

    @Test
    void nullLockIsIgnoredOnEveryRecordPath() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        assertDoesNotThrow(() -> {
            detector.registerLock(null, "n");
            detector.recordLockAcquired(null, "t");
            detector.recordLockReleased(null, "t");
            detector.recordLockTimeout(null);
            detector.analyze();
        });
    }
}
