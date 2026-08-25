package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.PublicLockExposureDetector;
import se.deversity.asynctest.example.service.SharedResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SharedResourceManager.
 *
 * ========================================================================
 * DETECTOR: PublicLockExposureDetector
 * ========================================================================
 *
 * THE BUG:
 * SharedResourceManager guards its state with `synchronized` instance methods, so
 * the lock is the object itself, and forResource() hands that object to anybody
 * who asks. A caller who wants two operations to be atomic will reach for
 * synchronized (manager) { ... }, because it works, and now unrelated code decides
 * how long every other thread waits.
 *
 * WHAT THE DETECTOR NEEDS, AND WHAT THIS EXAMPLE USED TO GIVE IT:
 * PublicLockExposureDetector reports the *intersection* of two sets: objects used
 * as a lock, and objects published to external code. It matches them by identity.
 *
 * This example used to record a ReentrantLock as published and the manager as
 * synchronized-upon: two different objects, so the intersection was empty and the
 * report was too. Three runs, no finding. See issue #346. Both hooks now report
 * the same instance, which is also the honest shape - the object that is the lock
 * is the object that gets handed out.
 *
 * WHY @Test PASSES:
 * A single thread is never blocked by another caller holding the lock.
 *
 * DETECTOR ENABLED HERE:
 * PublicLockExposureDetector — an object used as its own lock and handed to
 * external code. It is the only one this demonstration switches on, so it is the
 * only one that can report.
 *
 * FIX:
 * Guard the state with a lock nobody else can name: private final Object lock =
 * new Object(), and synchronized (lock) inside the methods. External code can
 * still call the methods; it can no longer take the lock.
 */
class SharedResourceManagerTest {

    private SharedResourceManager manager;

    @BeforeEach
    void setUp() {
        SharedResourceManager.resetDirectory();
        SharedResourceManager.observePublication(published -> { });
        manager = SharedResourceManager.forResource("inventory");
    }

    @AfterEach
    void tearDown() {
        SharedResourceManager.observePublication(published -> { });
        SharedResourceManager.resetDirectory();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testAccessResource_singleThread_incrementsValue() {
        assertEquals(1, manager.accessResource(), "First access should return 1");
    }

    @Test
    void testAccessResource_multipleCalls_sequential() {
        manager.accessResource();
        manager.accessResource();
        assertEquals(3, manager.accessResource(), "Three sequential accesses should return 3");
    }

    /**
     * The half of the bug that needs no detector: an external caller really can take the lock
     * this object uses for its own state, and hold it.
     */
    @Test
    void testExternalCaller_canHoldTheManagersOwnLock() throws Exception {
        Thread other = new Thread(() -> manager.accessResource());
        synchronized (manager) {
            other.start();
            other.join(150);
            assertTrue(other.isAlive(),
                    "the other thread is stuck on a lock this test took, from outside the class");
        }
        other.join(2000);
        assertFalse(other.isAlive(), "and it gets in once the external holder lets go");
    }

    /**
     * The detector's positive direction: the same instance both locked upon and published.
     */
    @Test
    void testPublicLockExposureDetector_lockedAndPublished_reports() {
        PublicLockExposureDetector detector = new PublicLockExposureDetector();
        wire(detector);

        SharedResourceManager published = SharedResourceManager.forResource("orders");
        published.observeLocking(() -> detector.recordSynchronizedOnThis(
                published, Thread.currentThread(), "SharedResourceManager"));
        published.accessResource();

        assertTrue(detector.analyze().hasIssues(),
                "an object that is both its own lock and public API is the exposure");
    }

    /**
     * And the other direction: an object used as a lock but never handed out is properly
     * encapsulated, and reporting it would flag every correctly written class.
     */
    @Test
    void testPublicLockExposureDetector_lockedButNotPublished_isSilent() {
        PublicLockExposureDetector detector = new PublicLockExposureDetector();

        Object privateLock = new Object();
        detector.recordSynchronizedOnThis(privateLock, Thread.currentThread(), "privateLock");
        // never published

        assertFalse(detector.analyze().hasIssues(),
                "a lock nobody outside the class can name is the fix, not a finding");
    }

    private void wire(PublicLockExposureDetector detector) {
        SharedResourceManager.observePublication(published ->
                detector.recordObjectPublished(
                        published, "returned from SharedResourceManager.forResource(...)"));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes public lock exposure
    // -----------------------------------------------------------------------

    /**
     * The bug: every thread looks the manager up, which publishes it, and then calls a method
     * that locks on it.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      SharedResourceManager uses synchronized(this) but is publicly exposed via
     *      returned from SharedResourceManager.forResource(...)
     * 3. Fix: move the guard to a private final Object nobody else can reach
     */
    @Disabled("Remove @Disabled to see exposed lock detected by PublicLockExposureDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false,
            detectPublicLockExposure = true, failOn = FailOn.LOW)
    void testAccessResource_concurrent_detectsExposedLock() {
        PublicLockExposureDetector detector = AsyncTestContext.publicLockExposureMonitor();
        SharedResourceManager.observePublication(published ->
                detector.recordObjectPublished(
                        published, "returned from SharedResourceManager.forResource(...)"));

        SharedResourceManager shared = SharedResourceManager.forResource("inventory");
        shared.observeLocking(() -> detector.recordSynchronizedOnThis(
                shared, Thread.currentThread(), "SharedResourceManager"));

        assertTrue(shared.accessResource() > 0, "Resource value must be positive");
    }
}
