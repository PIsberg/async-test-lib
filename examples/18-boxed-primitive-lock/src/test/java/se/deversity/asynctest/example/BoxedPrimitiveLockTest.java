package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the BoxedPrimitiveLockDetector (Phase 12).
 *
 * ============================================================
 * NOTE: BoxedPrimitiveLockDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A session registry uses a shared Integer (the session count,
 * range 0–127) as a lock object. The JVM caches all Integer.valueOf()
 * results in the range -128..127, so any other code that happens to
 * synchronize on Integer.valueOf(N) for the same N shares your monitor —
 * causing surprising contention with completely unrelated code.
 *
 * WHY @Test PASSES: With only one logical "test" thread the synchronization
 * always succeeds and the counter increments correctly.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector identifies the lock
 * object as a cached Integer and reports the hazard immediately.
 */
class BoxedPrimitiveLockTest {

    static class SessionRegistry {
        private final AtomicInteger sessionCount = new AtomicInteger(0);

        // Buggy: locks on a cached Integer — JVM-global shared monitor
        int registerSession(String userId) {
            Integer lockObj = sessionCount.get(); // cached Integer (0-127)
            synchronized (lockObj) {              // BUG: this lock is shared globally!
                return sessionCount.incrementAndGet();
            }
        }

        // Fixed: dedicated lock object
        private final Object lock = new Object();
        int registerSessionFixed(String userId) {
            synchronized (lock) {
                return sessionCount.incrementAndGet();
            }
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_registerSession_singleThread() {
        SessionRegistry registry = new SessionRegistry();
        int id1 = registry.registerSession("alice");
        int id2 = registry.registerSession("bob");
        assertEquals(1, id1);
        assertEquals(2, id2);
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectBoxedPrimitiveLock = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectBoxedPrimitiveLock_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.boxedPrimitiveLockDetector();
        //   Integer lockObj = sessionCount.get(); // cached Integer
        //   d.recordLockAcquire(lockObj, Thread.currentThread(), "SessionRegistry:12");
        //   synchronized (lockObj) { ... } // flagged!
        //
        // The detector will report "Thread '...' synchronized on cached Integer(N) at
        // [SessionRegistry:12] — this is a JVM-global shared instance."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — dedicated private lock object
    // =========================================================================

    @Test
    void part3_fixed_dedicatedLock() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        int threads = 4;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        AtomicInteger lastId = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String user = "user-" + i;
            new Thread(() -> {
                lastId.set(registry.registerSessionFixed(user));
                latch.countDown();
            }).start();
        }
        latch.await();
        assertEquals(threads, lastId.get() > 0 ? registry.sessionCount.get() : -1);
    }
}
