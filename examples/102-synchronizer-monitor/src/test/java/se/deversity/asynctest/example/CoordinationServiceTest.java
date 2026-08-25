package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.SynchronizerMonitor;
import se.deversity.asynctest.example.service.CoordinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
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
 * CoordinationService.execute() acquires a Semaphore, then a ReentrantLock inside
 * it for the same mutual exclusion, then counts down a CountDownLatch nobody
 * awaits: three primitives for one operation, and an exception between any two of
 * them leaves the rest inconsistent.
 *
 * The latch is the part this detector can see. It is meant to be a start gate for
 * expectedParties workers. It is built with a count of 1, so it opens on the first
 * arrival, and then replaced with a fresh one, so the gate the other workers are
 * holding is discarded and the replacement has never heard of anybody. No gate
 * ever gathers more than one party.
 *
 * WHY @Test PASSES:
 * One worker arriving at a gate built for one worker is a gate working.
 * testExecute_replacesTheGateEveryTime shows the replacement itself, with no
 * detector involved.
 *
 * WHY @AsyncTest DETECTS:
 * SynchronizerMonitor counts arrivals per synchronizer instance and reports one
 * that fewer parties reached than it was registered for. Registering each gate
 * for expectedParties and recording the arrival that actually happens gives it
 * exactly that.
 *
 * This example used to register the Semaphore with expectedParties = 1 and record
 * one arrival per body execution. One arrival out of one expected is a barrier
 * working, so the report was empty three runs out of three. See issue #346.
 *
 * FIX:
 * One CountDownLatch(expectedParties), created once and never replaced; or a
 * single ReentrantLock with a Condition on it.
 */
class CoordinationServiceTest {

    /** How many workers the start gate is meant to gather. */
    private static final int PARTIES = 8;

    private CoordinationService service;

    @BeforeEach
    void setUp() {
        service = new CoordinationService(PARTIES);
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

    /**
     * The bug with no detector involved: the gate a worker arrives at is not the gate the next
     * worker will find, so no gate ever gathers more than one of them.
     */
    @Test
    void testExecute_replacesTheGateEveryTime() throws Exception {
        CountDownLatch before = service.getLatch();

        service.execute(() -> { });

        assertEquals(0, before.getCount(), "the gate this worker found was counted down");
        assertNotSame(before, service.getLatch(),
                "and then thrown away, so the next worker arrives somewhere else");
    }

    /**
     * The monitor's positive direction, driven by the real service: a gate registered for
     * PARTIES workers that only one ever reaches.
     */
    @Test
    void testSynchronizerMonitor_gateReachedByOneOfEight_reports() throws Exception {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        service.observeGate(
                gate -> {
                    monitor.registerSynchronizer(gate, service.getExpectedParties());
                    monitor.recordBarrierArrival(gate);
                },
                monitor::recordBarrierAdvance);

        service.execute(() -> { });

        assertTrue(monitor.analyzeSynchronizers().hasIssues(),
                "a gate registered for " + PARTIES + " parties that one worker reached");
    }

    /**
     * And the other direction: a gate that every party actually reaches. This is what the
     * service is trying to be, and a monitor that reported it would report every barrier.
     */
    @Test
    void testSynchronizerMonitor_gateReachedByEveryParty_isSilent() throws Exception {
        SynchronizerMonitor monitor = new SynchronizerMonitor();
        CountDownLatch gate = new CountDownLatch(PARTIES);
        monitor.registerSynchronizer(gate, PARTIES);

        Thread[] workers = new Thread[PARTIES];
        for (int i = 0; i < PARTIES; i++) {
            workers[i] = new Thread(() -> {
                monitor.recordBarrierArrival(gate);
                gate.countDown();
                monitor.recordBarrierAdvance(gate);
            }, "worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join(5000);
        }

        assertEquals(0, gate.getCount(), "every party arrived, so the gate opened");
        assertFalse(monitor.analyzeSynchronizers().hasIssues(),
                "a gate all its parties reached is a gate working");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the over-synchronization
    // -------------------------------------------------------------------------

    /**
     * Eight workers call execute(), each expecting to meet the other seven at the start gate.
     * None of them does: the gate is built for one and replaced after every call.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      CountDownLatch: 1/8 parties arrived
     * 3. To fix: one CountDownLatch(expectedParties), created once and never replaced; or a
     *    single ReentrantLock with a Condition on it
     */
    @Disabled("Remove @Disabled to see the bug detected by SynchronizerMonitor")
    @AsyncTest(threads = PARTIES, invocations = 5, detectAll = false,
            monitorSynchronizers = true, failOn = FailOn.LOW)
    void test_concurrent_detectsOverSynchronization() throws Exception {
        // This demonstration used to register the Semaphore with expectedParties = 1 and record
        // one arrival per body execution. One arrival out of one expected is a barrier working,
        // so the report was empty three runs out of three. The monitor reports a synchronizer
        // that fewer parties reached than it was registered for, and the thing this service gets
        // wrong is exactly that. See issue #346.
        SynchronizerMonitor monitor = AsyncTestContext.synchronizerMonitor();
        service.observeGate(
                gate -> {
                    monitor.registerSynchronizer(gate, service.getExpectedParties());
                    monitor.recordBarrierArrival(gate);
                },
                monitor::recordBarrierAdvance);

        service.execute(() -> { });
    }
}
