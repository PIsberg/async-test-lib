package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SessionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SessionCache.
 *
 * ========================================================================
 * DETECTOR: NonAtomicConcurrentMapUpdateDetector
 * ========================================================================
 *
 * THE BUG:
 * SessionCache.getOrCreate() performs a containsKey()-then-put() on a ConcurrentMap.
 * ConcurrentMap makes each single operation atomic, but the check-then-act compound
 * operation is NOT atomic. Two threads can both observe the key as absent and both
 * put() a freshly-created session — one session silently overwrites the other.
 *
 * WHY @Test PASSES:
 * Single-threaded tests always complete the containsKey/put/get sequence before any
 * other thread runs, so the compound operation never interleaves. The bug is invisible.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads hammering the same user key, NonAtomicConcurrentMapUpdateDetector
 * observes the non-atomic check-then-act pattern from multiple threads against the
 * same ConcurrentMap and reports the race.
 *
 * FIX:
 * Replace the check-then-act with an atomic compound operation:
 * sessions.computeIfAbsent(userId, id -> "session-" + System.nanoTime())
 * (or putIfAbsent / merge).
 */
class SessionCacheTest {

    private SessionCache cache;

    @BeforeEach
    void setUp() {
        cache = new SessionCache();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testGetOrCreate_singleThread_returnsValue() {
        String session = cache.getOrCreate("user-1");
        assertNotNull(session);
        assertTrue(session.startsWith("session-"));
    }

    @Test
    void testGetOrCreate_sameUser_returnsStableValue() {
        String first = cache.getOrCreate("user-1");
        String second = cache.getOrCreate("user-1");
        assertEquals(first, second);
    }

    @Test
    void testGetOrCreate_differentUsers_returnDifferentSessions() {
        String a = cache.getOrCreate("user-a");
        String b = cache.getOrCreate("user-b");
        assertNotEquals(a, b);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the non-atomic check-then-act bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads concurrently call getOrCreate() for the same user.
     * NonAtomicConcurrentMapUpdateDetector records the check-then-act pattern
     * and reports that the compound containsKey-then-put runs non-atomically
     * against the same ConcurrentMap from multiple threads.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: replace getOrCreate with computeIfAbsent
     */
    @Disabled("Remove @Disabled to see the bug detected by NonAtomicConcurrentMapUpdateDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectConcurrentMapCheckThenAct = true, failOn = FailOn.LOW)
    void test_concurrent_detectsCheckThenAct() {
        Thread thread = Thread.currentThread();
        String userId = "user-shared";

        // Instrument: record the non-atomic check-then-act on the shared ConcurrentMap
        AsyncTestContext.nonAtomicConcurrentMapUpdateDetector()
                .recordCheckThenAct(cache.getSessions(), userId, "getOrCreate", thread);

        cache.getOrCreate(userId);
    }
}
