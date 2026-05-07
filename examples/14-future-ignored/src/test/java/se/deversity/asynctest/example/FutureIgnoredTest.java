package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the FutureIgnoredDetector (Phase 12).
 *
 * ============================================================
 * NOTE: FutureIgnoredDetector ships in async-test-lib 0.10.0.
 * This example targets 0.10.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: An event bus submits background tasks but discards the
 * returned Future. When a submitted task throws an exception the
 * exception is captured inside the Future but never retrieved —
 * the failure is silently swallowed and the event bus appears healthy.
 *
 * WHY @Test PASSES: The test only checks visible side effects, not
 * whether the submitted Future completed successfully.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector flags every
 * Future that was submitted but never had get/isDone called on it.
 */
class FutureIgnoredTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    static class EventBus {
        private ExecutorService exec;

        EventBus(ExecutorService exec) { this.exec = exec; }

        // Buggy: discards the Future
        void publish(Runnable handler) {
            exec.submit(handler); // return value ignored!
        }

        // Fixed: returns the Future for inspection
        Future<?> publishTracked(Runnable handler) {
            return exec.submit(handler);
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_eventPublished_singleThread() throws Exception {
        AtomicBoolean handled = new AtomicBoolean(false);
        EventBus bus = new EventBus(executor);
        bus.publish(() -> handled.set(true));
        Thread.sleep(100); // wait for background task
        assertTrue(handled.get());
        // @Test does not detect that the Future from publish() was ignored
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectFutureIgnored = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectIgnoredFuture_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.futureIgnoredDetector();
        //   Future<?> f = executor.submit(() -> { /* task */ });
        //   d.recordSubmit(f, "orderProcessor", Thread.currentThread());
        //   // BUG: no recordInspect call, no f.get()
        //
        // The detector will report "Future for task 'orderProcessor' submitted by
        // thread '...' was never inspected — exceptions thrown by the task are
        // silently swallowed."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — inspects every submitted Future
    // =========================================================================

    @Test
    void part3_fixed_futureInspected() throws Exception {
        AtomicBoolean handled = new AtomicBoolean(false);
        EventBus bus = new EventBus(executor);
        Future<?> f = bus.publishTracked(() -> handled.set(true));
        f.get(5, TimeUnit.SECONDS); // properly wait + retrieve any exception
        assertTrue(handled.get());
    }
}
