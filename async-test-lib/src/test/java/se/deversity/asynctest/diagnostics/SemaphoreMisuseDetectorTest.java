package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SemaphoreMisuseDetector.
 */
public class SemaphoreMisuseDetectorTest {

    @Test
    void testNormalSemaphoreUsage() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "resource-pool", 2);
        detector.recordAcquire(semaphore, "resource-pool");
        detector.recordRelease(semaphore, "resource-pool");
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testPermitLeakDetection() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "leaky-pool", 2);
        detector.recordAcquire(semaphore, "leaky-pool");
        detector.recordAcquire(semaphore, "leaky-pool");
        detector.recordRelease(semaphore, "leaky-pool");
        // Missing one release - leak!
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect permit leak");
        assertFalse(report.permitLeaks.isEmpty(), "Should report permit leaks");
    }

    @Test
    void testOverReleaseDetection() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "over-release-pool", 2);
        detector.recordAcquire(semaphore, "over-release-pool");
        detector.recordRelease(semaphore, "over-release-pool");
        detector.recordRelease(semaphore, "over-release-pool");
        // Extra release!
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect over-release");
        assertFalse(report.overReleases.isEmpty(), "Should report over-releases");
    }

    @Test
    void testUnreleasedPermitsDetection() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(2);
        
        detector.registerSemaphore(semaphore, "unreleased-pool", 2);
        semaphore.acquire(); // Actually acquire a permit
        detector.recordAcquire(semaphore, "unreleased-pool");
        // Never release - permits remain acquired
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect unreleased permits");
        assertFalse(report.unreleasedPermits.isEmpty(), "Should report unreleased permits");
    }

    @Test
    void testThreadActivityTracking() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(3);
        
        detector.registerSemaphore(semaphore, "multi-thread-pool", 3);
        
        // Simulate multiple threads
        Thread t1 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
        });
        
        Thread t3 = new Thread(() -> {
            detector.recordAcquire(semaphore, "multi-thread-pool");
            detector.recordRelease(semaphore, "multi-thread-pool");
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
        assertTrue(report.threadActivity.stream().filter(a -> a.startsWith("multi-thread-pool: ")).findFirst().orElse("").contains("3 threads"),
                   "Should report 3 threads participated");
    }

    @Test
    void testAutoRegistration() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(1);
        
        // Record without explicit registration - should auto-register
        detector.recordAcquire(semaphore, "auto-registered");
        detector.recordRelease(semaphore, "auto-registered");
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Auto-registered semaphore should work correctly");
    }

    @Test
    void testNullSafety() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        
        // Should not throw on null inputs
        detector.recordAcquire(null, "null-semaphore");
        detector.recordRelease(null, "null-semaphore");
        detector.registerSemaphore(null, "null", 1);
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore semaphore = new Semaphore(1);
        
        detector.registerSemaphore(semaphore, "test-pool", 1);
        detector.recordAcquire(semaphore, "test-pool");
        // Leak one permit
        
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SEMAPHORE MISUSE DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Permit Leaks"), "Report should mention permit leaks");
    }

    /**
     * Two semaphores may share a name. Each keeps its own thread-activity line; filed under the
     * name, the second semaphore's line overwrote the first's (#789).
     */
    @Test
    void twoSemaphoresWithTheSameNameEachKeepTheirThreadActivity() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore balanced = new Semaphore(2);
        Semaphore held = new Semaphore(2);
        detector.registerSemaphore(balanced, "pool", 2);
        detector.registerSemaphore(held, "pool", 2);
        detector.recordAcquire(balanced, "pool");
        detector.recordRelease(balanced, "pool");
        detector.recordAcquire(held, "pool");
        detector.recordAcquire(held, "pool");

        String report = detector.analyze().toString();
        assertTrue(report.contains("pool: 1 threads acquired, 1 threads released, max concurrent: 1"),
                "the balanced semaphore's line survives beside the held one's: " + report);
        assertTrue(report.contains("pool: 1 threads acquired, 0 threads released, max concurrent: 2"),
                "the held semaphore's line survives beside the balanced one's: " + report);
    }

    // A semaphore that starts empty is a signal: one thread releases, another acquires. The agent
    // registers nothing up front, so the first event such a semaphore produces is a release.

    /**
     * Three signals sent, three awaited. The releases come first, and they used to be dropped
     * because nothing had registered the semaphore yet, so balanced use read as three leaks.
     */
    @Test
    void releasesRecordedBeforeTheFirstAcquireAreCounted() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore signal = new Semaphore(0);
        for (int i = 0; i < 3; i++) {
            detector.recordRelease(signal, "signal");
        }
        for (int i = 0; i < 3; i++) {
            detector.recordAcquire(signal, "signal");
        }

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), "three signals, three awaited: " + report);
    }

    /** A signal nobody has awaited yet is a surplus, not an over-release: no holder returned it. */
    @Test
    void aSignalSentAheadOfItsWaiterMayEndWithASurplus() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore signal = new Semaphore(0);
        for (int i = 0; i < 3; i++) {
            detector.recordRelease(signal, "signal");
        }
        detector.recordAcquire(signal, "signal");
        detector.recordAcquire(signal, "signal");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), "three signals, two awaited: " + report);
    }

    /** The other direction: more permits taken than were ever sent or returned is still a leak. */
    @Test
    void aSignalAwaitedMoreOftenThanItWasSentStillLeaks() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore signal = new Semaphore(2);
        detector.recordRelease(signal, "signal");
        detector.recordAcquire(signal, "signal");
        detector.recordAcquire(signal, "signal");
        detector.recordAcquire(signal, "signal");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertEquals(1, report.permitLeaks.size(), report.toString());
        assertTrue(report.permitLeaks.get(0).contains("acquired 3 times but released only 1 times"),
                "the early release is in the balance: " + report);
    }

    /** A holder that returns its permit twice is an over-release, with or without registration. */
    @Test
    void aPermitReturnedTwiceByItsHolderIsStillAnOverRelease() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore mutex = new Semaphore(1);
        detector.recordAcquire(mutex, "mutex");
        detector.recordRelease(mutex, "mutex");
        detector.recordRelease(mutex, "mutex");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertEquals(1, report.overReleases.size(), report.toString());
    }

    /** A registered capacity is the caller's own statement, so a surplus over it is reported. */
    @Test
    void aRegisteredSemaphoreReleasedFirstIsHeldToItsDeclaredPermits() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore pool = new Semaphore(1);
        detector.registerSemaphore(pool, "pool", 1);
        detector.recordRelease(pool, "pool");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertEquals(1, report.overReleases.size(), report.toString());
    }
}
