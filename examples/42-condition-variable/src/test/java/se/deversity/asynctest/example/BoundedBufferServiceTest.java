package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.example.service.BoundedBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BoundedBufferService.
 *
 * ========================================================================
 * DETECTOR: ConditionVariableDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same service under @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * BoundedBufferService.put() signals notFull after adding an item, but consumers are parked
 * on notEmpty. A consumer that was already waiting when the item arrived is never woken.
 *
 * WHY @Test PASSES:
 * A single thread calling put() then take() never waits: the item is already there when
 * take() checks the buffer, so the wrong signal has no visible effect.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * Each worker parks a consumer on an empty buffer and then produces one item. The service
 * reports every await, await exit and signal to ConditionVariableDetector through its Probe.
 * When the run is analysed the lock shows the consumers still parked on notEmpty while the
 * predicate registered for it (the buffer is not empty) holds: a stuck waiter, which the
 * detector reports.
 *
 * WHAT IS NOT REPORTED:
 * A signal made while nobody waits, and a poll that times out, are how correct code runs,
 * so the detector does not report either on its own.
 *
 * DETECTORS TRIGGERED:
 *   ConditionVariableDetector — stuck waiters on "not-empty"
 *
 * FIX: put() signals notEmpty instead of notFull.
 */
class BoundedBufferServiceTest {

    private BoundedBufferService service;

    @BeforeEach
    void setUp() {
        service = new BoundedBufferService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testPutAndTake_singleThread_works() throws InterruptedException {
        service.put("item-1");
        String result = service.take();
        assertEquals("item-1", result);
    }

    @Test
    void testSize_afterPut() throws InterruptedException {
        service.put("a");
        service.put("b");
        assertEquals(2, service.size());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — a consumer parked before the item arrives is stranded
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see the stranded consumer reported by ConditionVariableDetector")
    @AsyncTest(threads = 4, invocations = 5, detectAll = false, detectConditionVariableIssues = true, failOn = FailOn.LOW)
    void testBuffer_concurrent_detectsStrandedConsumer() throws InterruptedException {
        ConditionVariableDetector monitor = AsyncTestContext.conditionVariableDetector();
        BoundedBufferService buffer = new BoundedBufferService(probe(monitor));
        // Registered with the lock that made each condition, and with what a consumer on
        // not-empty waits for: the lock shows who is parked, the predicate says whether they
        // should be. A consumer parked while the buffer is empty is idle, not stuck.
        monitor.registerCondition(buffer.getLock(), buffer.getNotEmpty(), () -> buffer.size() > 0, "not-empty");
        monitor.registerCondition(buffer.getLock(), buffer.getNotFull(), "not-full");

        // A consumer that is already waiting when the item arrives. Daemon, and bounded well
        // past the end of the run, so a stranded consumer neither hangs the build nor leaves
        // before the detector looks.
        Thread consumer = new Thread(() -> {
            try {
                buffer.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.setDaemon(true);
        consumer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!buffer.hasWaitingConsumers()) {
            assertTrue(System.nanoTime() < deadline, "the consumer never started waiting");
            Thread.onSpinWait();
        }

        buffer.put("item-" + Thread.currentThread().threadId());   // signals the wrong condition
    }

    private static BoundedBufferService.Probe probe(ConditionVariableDetector monitor) {
        return new BoundedBufferService.Probe() {
            @Override
            public void awaiting(Condition condition) {
                monitor.recordAwait(condition, null);
            }

            @Override
            public void awaitExited(Condition condition, boolean timedOut) {
                monitor.recordAwaitExit(condition, null, timedOut);
            }

            @Override
            public void signalling(Condition condition, boolean all) {
                monitor.recordSignal(condition, null, all);
            }
        };
    }
}
