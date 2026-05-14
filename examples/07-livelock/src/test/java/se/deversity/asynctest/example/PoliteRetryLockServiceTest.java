package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.example.service.PoliteRetryLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PoliteRetryLockService.
 *
 * ========================================================================
 * DETECTOR: LivelockDetector
 * ========================================================================
 *
 * This test demonstrates a livelock in a distributed lock service that uses
 * a zero-delay "polite yield" retry strategy:
 * - A sequential @Test PASSES — one node acquires the lock immediately
 * - The @AsyncTest with 6 threads spinning in lock-step enters a livelock:
 *   all threads are RUNNABLE and consuming CPU, but none of them ever
 *   acquires the lock long enough to complete useful work
 *
 * THE BUG:
 * When N threads simultaneously call {@code acquireLock()}, all of them
 * call CAS at roughly the same time. One wins; the others see the lock
 * is held and call {@code Thread.yield()} before retrying. When the winner
 * releases, all N-1 losers retry simultaneously and the whole cycle repeats.
 * The retry loop spins through hundreds of attempts without ever distributing
 * work across threads.
 *
 * Unlike a deadlock (threads are BLOCKED), livelock threads are RUNNABLE —
 * they appear healthy from the outside but make no actual progress.
 *
 * WHY @Test PASSES:
 * A single thread encounters no contention. The first CAS always succeeds,
 * the critical section runs immediately, and the method returns {@code true}.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * LivelockDetector captures thread-state snapshots at the end of each
 * invocation round. It looks for threads that:
 *   - Flip rapidly between RUNNABLE and other states (rapid state cycling)
 *   - Accumulate CPU time without completing work (no progress pattern)
 *   - Are never in BLOCKED state yet never make forward progress
 * Under 6 concurrent threads hitting the tight retry loop, many invocations
 * exhaust all MAX_RETRIES and return false — work does not progress.
 *
 * DETECTORS TRIGGERED:
 * LivelockDetector — rapid state cycling and starvation in the retry loop
 *
 * FIX:
 * Introduce randomised exponential back-off before each retry:
 *   {@code Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs))}
 * Different random wait times break the lock-step symmetry — one node will
 * always wait longer, giving other nodes time to acquire and release the lock.
 */
class PoliteRetryLockServiceTest {

    private PoliteRetryLockService service;

    @BeforeEach
    void setUp() {
        service = new PoliteRetryLockService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no contention visible
    // -------------------------------------------------------------------------

    @Test
    void testAcquireLock_singleThread_succeeds() {
        boolean acquired = service.acquireLock("node-1");

        assertTrue(acquired, "Single-threaded acquisition should always succeed");
        assertEquals(1, service.getAcquisitionCount());
        assertNull(service.getLockHolder(), "Lock should be released after acquisition");
    }

    @Test
    void testAcquireLock_sequential_multipleNodes() {
        boolean a = service.acquireLock("node-1");
        boolean b = service.acquireLock("node-2");
        boolean c = service.acquireLock("node-3");

        assertTrue(a, "node-1 should acquire sequentially");
        assertTrue(b, "node-2 should acquire sequentially");
        assertTrue(c, "node-3 should acquire sequentially");
        assertEquals(3, service.getAcquisitionCount());
    }

    @Test
    void testAcquireLockFixed_singleThread_succeeds() throws InterruptedException {
        boolean acquired = service.acquireLockFixed("node-1");

        assertTrue(acquired, "Fixed single-threaded acquisition should always succeed");
        assertEquals(1, service.getAcquisitionCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the livelock
    // -------------------------------------------------------------------------

    /**
     * The bug: 6 threads all call acquireLock() with their own node IDs. Because
     * all threads retry immediately on contention (zero-delay yield), they cycle
     * through acquire-contend-yield-retry in lock-step. Many invocations exhaust
     * all MAX_RETRIES attempts and return false, yet the threads were continuously
     * RUNNABLE — the definition of a livelock.
     *
     * LivelockDetector is a Phase 1 detector. It captures a snapshot of all
     * thread states at the end of each invocation round and analyses them for
     * rapid state-cycling and no-progress patterns. No manual instrumentation
     * calls are needed in the test body.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — LivelockDetector will flag livelock candidates and
     *    threads with no progress
     * 3. Fix: replace acquireLock() with acquireLockFixed() in the test body
     */
    @Disabled("Remove @Disabled to see livelock detected by LivelockDetector")
    @AsyncTest(threads = 6, invocations = 30, detectLivelocks = true, timeoutMs = 10000)
    void testAcquireLock_concurrent_detectsLivelock() {
        String nodeId = "node-" + Thread.currentThread().threadId();

        // Under 6-thread contention the tight retry loop spins up to MAX_RETRIES
        // times without completing. LivelockDetector observes threads rapidly
        // cycling between RUNNABLE states without making forward progress.
        boolean acquired = service.acquireLock(nodeId);

        // Many invocations will fail (return false) because the livelock exhausts
        // all retry attempts — this assertion demonstrates the lack of progress.
        // The real signal is in the LivelockDetector report, not this assertion.
        if (!acquired) {
            throw new AssertionError("Lock acquisition failed after MAX_RETRIES — "
                    + "livelock: threads are active but no thread made progress");
        }
    }

    /**
     * Fixed version: randomised exponential back-off breaks the lock-step symmetry.
     * Different wait times mean that at least one node will be sleeping while another
     * holds the lock — they no longer all retry simultaneously.
     */
    @Test
    void testAcquireLockFixed_singleThread_noLivelock() throws InterruptedException {
        // Verify the fixed version works correctly under single-threaded conditions
        assertTrue(service.acquireLockFixed("node-A"));
        assertTrue(service.acquireLockFixed("node-B"));
        assertTrue(service.acquireLockFixed("node-C"));
        assertEquals(3, service.getAcquisitionCount());
    }
}
