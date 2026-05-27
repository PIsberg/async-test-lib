package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.CoordinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for CoordinationService.
 *
 * ========================================================================
 * DETECTOR: SynchronizerMonitor
 * ========================================================================
 *
 * THE BUG:
 * CoordinationService.execute() acquires a Semaphore, a ReentrantLock, and a
 * CountDownLatch in sequence for every single operation. If an exception occurs
 * between any two acquisitions the other primitives are left in an inconsistent
 * state (e.g. latch counted down but semaphore still held). Under concurrency,
 * threads arrive at the Semaphore in different orders and the latch reset races
 * with the next acquire, leaving the barrier partially populated.
 *
 * WHY @Test PASSES:
 * Single-threaded execution acquires and releases all three primitives in the
 * exact same order every time, with no interleaving and no race on the latch reset.
 *
 * WHY @AsyncTest DETECTS:
 * With multiple threads, SynchronizerMonitor records arrivals and advances on the
 * Semaphore (treated as a barrier with 1 party). Threads that cannot acquire the
 * semaphore block; those that do arrive and advance but the total count does not
 * match expected parties, or threads arrive multiple times after the latch resets.
 *
 * FIX:
 * Replace the three primitives with a single ReentrantLock and a Condition variable.
 */
class CoordinationServiceTest {

    private CoordinationService service;

    @BeforeEach
    void setUp() {
        service = new CoordinationService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testExecute_singleThread_runsTask() throws Exception {
        AtomicInteger count = new AtomicInteger();
        service.execute(count::incrementAndGet);
        assertEquals(1, count.get());
    }

    @Test
    void testGetSemaphore_nonNull() {
        assertNotNull(service.getSemaphore());
    }

    @Test
    void testGetLock_nonNull() {
        assertNotNull(service.getLock());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the over-synchronization
    // -------------------------------------------------------------------------

    /**
     * Multiple threads all call execute() concurrently. SynchronizerMonitor
     * registers the Semaphore as a synchronizer and records each thread's
     * arrival and advance. Partial arrivals (fewer than expected) and duplicate
     * arrivals from latch resets are flagged.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: replace the three primitives with a single ReentrantLock + Condition
     */
    @Disabled("Remove @Disabled to see the bug detected by SynchronizerMonitor")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, monitorSynchronizers = true)
    void test_concurrent_detectsOverSynchronization() {
        var mon = AsyncTestContext.synchronizerMonitor();
        var semaphore = service.getSemaphore();

        // Register the semaphore as a 1-party synchronizer
        mon.registerSynchronizer(semaphore, 1);
        mon.recordBarrierArrival(semaphore);

        try {
            service.execute(() -> {
                // intentionally empty task body — we're testing synchronizer behavior
            });
            mon.recordBarrierAdvance(semaphore);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
