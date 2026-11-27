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

    /**
     * The run's thread waits on a signal that a pool thread, a callback or a thread the body
     * started sends: the permit it took came from that release, so nothing leaked.
     */
    @Test
    void aPermitTheRunTookAndAHelperThreadSentIsNotALeak() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore done = new Semaphore(0);
        detector.recordHelperRelease(done, "done");
        detector.recordAcquire(done, "done");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), report.toString());
    }

    /** The same hand-over with the permit taken first and returned by the helper afterwards. */
    @Test
    void aPermitTheRunTookAndAHelperThreadReturnedIsNotALeak() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore mutex = new Semaphore(1);
        detector.recordAcquire(mutex, "mutex");
        detector.recordHelperRelease(mutex, "mutex");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), report.toString());
    }

    /**
     * A helper thread may outlive the run, so a permit it holds when the run is analysed is not
     * the run's leak, and a signal it sent that nobody awaited is not a permit returned twice.
     */
    @Test
    void whatAHelperThreadTakesOrSendsAloneIsNeverAFinding() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore held = new Semaphore(1);
        Semaphore unawaited = new Semaphore(0);
        detector.recordHelperAcquire(held, "held");
        detector.recordHelperRelease(unawaited, "unawaited");
        detector.recordHelperRelease(unawaited, "unawaited");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), report.toString());
    }

    /** A helper that takes a permit and returns it has returned its own, not the run's. */
    @Test
    void aHelperThreadsOwnAcquireAndReleaseDoNotCoverTheRunsLeak() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore pool = new Semaphore(2);
        detector.recordAcquire(pool, "pool");
        detector.recordHelperAcquire(pool, "pool");
        detector.recordHelperRelease(pool, "pool");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertEquals(1, report.permitLeaks.size(), report.toString());
    }

    /** A permit a helper took and the run's thread returned is a hand-over, not an over-release. */
    @Test
    void aPermitAHelperThreadTookAndTheRunReturnedIsNotAnOverRelease() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore mutex = new Semaphore(2);
        detector.recordAcquire(mutex, "mutex");
        detector.recordRelease(mutex, "mutex");
        detector.recordHelperAcquire(mutex, "mutex");
        detector.recordRelease(mutex, "mutex");

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertFalse(report.hasIssues(), report.toString());
    }

    private static void onItsOwnThread(Runnable work) {
        Thread thread = new Thread(work);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A signal that starts with permits and is acquired first (#1292): the waiter takes one, the
     * signalling thread, which never held any, releases three. No holder returned a permit twice.
     */
    @Test
    void aSignalWithStartingPermitsAcquiredFirstIsNotAnOverRelease() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore signal = new Semaphore(2);
        onItsOwnThread(() -> detector.recordAcquire(signal, "signal"));
        onItsOwnThread(() -> {
            for (int i = 0; i < 3; i++) {
                detector.recordRelease(signal, "signal");
            }
        });

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertTrue(report.overReleases.isEmpty(), "the releasing thread held nothing to return: " + report);
    }

    /** Acquired on one thread and released on another is a hand-off, which a semaphore allows. */
    @Test
    void aPermitHandedToAnotherThreadToReleaseIsNotAnOverRelease() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore permits = new Semaphore(1);
        onItsOwnThread(() -> detector.recordAcquire(permits, "permits"));
        onItsOwnThread(() -> detector.recordRelease(permits, "permits"));

        assertFalse(detector.analyze().hasIssues(), "one permit taken, one returned");
    }

    /** A thread that took one permit and returned two is an over-release whoever else signals. */
    @Test
    void aHolderThatReturnsTwiceIsAnOverReleaseBesideASignal() {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        Semaphore mixed = new Semaphore(1);
        onItsOwnThread(() -> {
            detector.recordAcquire(mixed, "mixed");
            detector.recordRelease(mixed, "mixed");
            detector.recordRelease(mixed, "mixed");
        });
        onItsOwnThread(() -> detector.recordRelease(mixed, "mixed"));

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();

        assertEquals(1, report.overReleases.size(), report.toString());
        assertTrue(report.overReleases.get(0).contains("1 extra release"), report.toString());
    }

    /** Runs {@code body} on a new thread and waits for it. */
    private static void onThread(Runnable body) throws InterruptedException {
        Thread thread = new Thread(body);
        thread.start();
        thread.join();
    }

    /**
     * #1501: a permit acquired on one thread and returned by another that acquired before. The
     * returning thread held nothing, so its release counted as returning twice, and with no
     * registered capacity that alone was a HIGH over-release, though the run acquired and released
     * the same number of permits.
     */
    @org.junit.jupiter.api.Test
    void aPermitReleasedByAnotherThreadIsNotAnOverRelease() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(1);
        Thread[] releaser = new Thread[1];
        java.util.concurrent.CountDownLatch handedOver = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ownUseDone = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        releaser[0] = new Thread(() -> {
            semaphore.acquireUninterruptibly(); // an earlier use of its own
            detector.recordAcquire(semaphore, "pool");
            detector.recordRelease(semaphore, "pool");
            semaphore.release();
            ownUseDone.countDown();
            try {
                handedOver.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            detector.recordRelease(semaphore, "pool"); // the permit the other thread took
            semaphore.release();
            done.countDown();
        });
        releaser[0].start();
        // The single permit is the releaser's until its own use is over: taken first by the other
        // thread, the releaser waited for it forever (a hang on a JDK 21 CI runner).
        org.junit.jupiter.api.Assertions.assertTrue(ownUseDone.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the releaser's own use finished");
        onThread(() -> {
            semaphore.acquireUninterruptibly();
            detector.recordAcquire(semaphore, "pool");
            handedOver.countDown(); // the work, and the permit with it, go to the releaser
        });
        org.junit.jupiter.api.Assertions.assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the releaser returned the handed-over permit");
        releaser[0].join(10_000);

        org.junit.jupiter.api.Assertions.assertTrue(detector.analyze().overReleases.isEmpty(),
                "two acquisitions and two releases: the second release returned a permit another"
                        + " thread took, " + detector.analyze().overReleases);
    }

    @org.junit.jupiter.api.Test
    void aThreadReturningMoreThanTheRunAcquiredIsAnOverRelease() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(1);
        onThread(() -> {
            semaphore.acquireUninterruptibly();
            detector.recordAcquire(semaphore, "pool");
            detector.recordRelease(semaphore, "pool");
            semaphore.release();
            detector.recordRelease(semaphore, "pool"); // once too often
            semaphore.release();
        });

        org.junit.jupiter.api.Assertions.assertFalse(detector.analyze().overReleases.isEmpty(),
                "one acquisition and two releases");
    }

    /** #1501: permits a thread drained and returned, after it used one through acquire before. */
    @org.junit.jupiter.api.Test
    void drainedPermitsReturnedAreNotAnOverRelease() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(3);
        onThread(() -> {
            semaphore.acquireUninterruptibly();
            detector.recordAcquire(semaphore, "pool");
            detector.recordRelease(semaphore, "pool");
            semaphore.release();
            int drained = semaphore.drainPermits();
            detector.recordDrain(semaphore, "pool", drained);
            for (int i = 0; i < drained; i++) {
                detector.recordRelease(semaphore, "pool");
            }
            semaphore.release(drained);
        });

        SemaphoreMisuseDetector.SemaphoreMisuseReport report = detector.analyze();
        org.junit.jupiter.api.Assertions.assertTrue(report.overReleases.isEmpty(),
                "the drained permits were the thread's to return: " + report.overReleases);
        org.junit.jupiter.api.Assertions.assertTrue(report.permitLeaks.isEmpty(), report.permitLeaks.toString());
    }

    @org.junit.jupiter.api.Test
    void aPoolDrainedToCloseItIsNotALeak() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(3);
        onThread(() -> detector.recordDrain(semaphore, "pool", semaphore.drainPermits()));

        org.junit.jupiter.api.Assertions.assertTrue(detector.analyze().permitLeaks.isEmpty(),
                "draining takes the permits out of use; it acquires nothing anyone owes back");
    }

    @org.junit.jupiter.api.Test
    void returningMoreThanWasDrainedIsAnOverRelease() throws InterruptedException {
        SemaphoreMisuseDetector detector = new SemaphoreMisuseDetector();
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(2);
        onThread(() -> {
            int drained = semaphore.drainPermits();
            detector.recordDrain(semaphore, "pool", drained);
            for (int i = 0; i <= drained; i++) { // one more than drained
                detector.recordRelease(semaphore, "pool");
                semaphore.release();
            }
        });

        org.junit.jupiter.api.Assertions.assertFalse(detector.analyze().overReleases.isEmpty(),
                "two permits drained, three returned");
    }
}
