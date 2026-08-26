package se.deversity.asynctest.example;

import se.deversity.asynctest.example.service.PoliteRetryLockService;
import org.junit.jupiter.api.BeforeEach;
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
 * WHY LivelockDetector DOES NOT REPORT IT:
 * Its rules are starvation (a thread that stays BLOCKED or WAITING with flat
 * CPU time) and rapid state cycling. A thread spinning in this retry loop is
 * RUNNABLE throughout, and the detector treats RUNNABLE as progress on purpose.
 * It also reads ThreadMXBean.dumpAllThreads, which does not report virtual
 * threads, and the runner's workers are virtual by default. Part 2 below says
 * so in full, and measures the retry burn directly instead.
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
    // Part 2: the retry burn, and why there is no @AsyncTest demonstration
    // -------------------------------------------------------------------------

    /**
     * This example carried a disabled {@code @AsyncTest} promising "livelock detected by
     * LivelockDetector". It never fired, and three separate things stood in the way, each of
     * which would have been enough on its own. See issue #362.
     *
     * <p>LivelockDetector does not report a busy spin. Its rules are starvation, meaning a
     * thread that stays BLOCKED or WAITING with flat CPU time, and rapid state cycling.
     * {@code madeProgress} returns true for any RUNNABLE thread, deliberately: a busy worker's
     * measured CPU time can look flat when several snapshots land inside one clock tick, and
     * reporting those as stuck produced findings against healthy JVMs. A thread spinning in
     * this retry loop is RUNNABLE the whole time.
     *
     * <p>It reads {@code ThreadMXBean.dumpAllThreads}, which does not report virtual threads,
     * and the runner's workers are virtual by default. Nothing about the workers reaches the
     * detector's history at all.
     *
     * <p>And the subject makes progress under contention. One node wins the CAS on every cycle
     * and runs its critical section, so {@code acquireLock} returns true; what is lost is the
     * work all the other nodes threw away, and that is a throughput bug rather than a stall.
     * The tests below measure it directly, which is what this example is actually good for.
     */

    @Test
    void testAcquireLock_whileAnotherNodeHoldsTheLock_burnsEveryRetry() {
        assertTrue(service.takeLockAsNode("node-holder"),
                "the holder takes the free lock first");
        try {
            assertFalse(service.acquireLock("node-A"),
                    "the lock is held for the whole call, so every attempt goes round again");
            assertEquals(0, service.getAcquisitionCount(),
                    "no critical section ran: two hundred attempts produced nothing");
            assertTrue(service.getContendedAttemptCount() >= 200,
                    "every retry hit the contended branch and yielded with no delay, which is "
                            + "the cost this design hides behind a boolean. Contended attempts: "
                            + service.getContendedAttemptCount());
        } finally {
            service.releaseLockAsNode();
        }
    }

    @Test

    void testAcquireLockFixed_whileAnotherNodeHoldsTheLock_sleepsInsteadOfSpinning()
            throws InterruptedException {
        assertTrue(service.takeLockAsNode("node-holder"), "the holder takes the free lock first");
        try {
            long before = service.getContendedAttemptCount();

            Thread node = new Thread(() -> {
                try {
                    service.acquireLockFixed("node-A");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "backing-off-node");
            node.setDaemon(true);
            node.start();
            Thread.sleep(300);

            assertEquals(before, service.getContendedAttemptCount(),
                    "300ms of contention and not one zero-delay retry: the fixed version is "
                            + "asleep between attempts, which is the whole difference. It is not "
                            + "joined here because running it out to MAX_RETRIES would take "
                            + "twenty seconds, which is also the point");
            node.interrupt();
        } finally {
            service.releaseLockAsNode();
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
