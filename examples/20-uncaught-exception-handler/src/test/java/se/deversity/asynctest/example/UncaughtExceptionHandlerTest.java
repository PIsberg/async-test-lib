package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the UncaughtExceptionHandlerDetector (Phase 12).
 *
 * ============================================================
 * NOTE: UncaughtExceptionHandlerDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A background worker thread throws an uncaught exception but
 * has no UncaughtExceptionHandler installed. The exception is routed to
 * the default ThreadGroup handler (stderr print) and the submitting code
 * has no way to detect that the task failed. The thread pool silently
 * creates a new replacement thread and work continues as if nothing
 * happened.
 *
 * WHY @Test PASSES: The test waits on join() — but join() returns when
 * the thread terminates regardless of whether it threw. The test does
 * not check whether the work completed successfully.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector records the
 * absence of a custom handler and flags the combination of "no handler"
 * + "exception thrown".
 */
class UncaughtExceptionHandlerTest {

    static class OrderProcessor {
        Thread processAsync(int orderId) {
            // Buggy: no UncaughtExceptionHandler set
            Thread t = new Thread(() -> {
                if (orderId < 0) {
                    throw new IllegalArgumentException("Invalid order: " + orderId);
                }
                // process...
            });
            t.start();
            return t;
        }

        Thread processAsyncFixed(int orderId, AtomicReference<Throwable> errorCapture) {
            Thread t = new Thread(() -> {
                if (orderId < 0) {
                    throw new IllegalArgumentException("Invalid order: " + orderId);
                }
            });
            // Fixed: install a handler that captures the exception for the submitter
            t.setUncaughtExceptionHandler((th, ex) -> errorCapture.set(ex));
            t.start();
            return t;
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_processOrder_singleThread() throws Exception {
        OrderProcessor proc = new OrderProcessor();
        Thread worker = proc.processAsync(42); // valid order
        worker.join(1000);
        assertFalse(worker.isAlive());
        // @Test doesn't detect that a negative orderId would throw silently
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 2, detectUncaughtExceptionHandler = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectMissingHandler_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.uncaughtExceptionHandlerDetector();
        //   Thread worker = new Thread(() -> { throw new RuntimeException("boom"); });
        //   // NOTE: no setUncaughtExceptionHandler() call
        //   d.recordThreadStart(worker);
        //   worker.start();
        //   try { worker.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        //   d.recordUncaughtException(worker, new RuntimeException("boom"));
        //
        // The detector will report "Thread '...' threw 'RuntimeException' but had no
        // custom UncaughtExceptionHandler — the exception was only printed to stderr."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — UncaughtExceptionHandler captures failures
    // =========================================================================

    @Test
    void part3_fixed_handlerCapturesException() throws Exception {
        OrderProcessor proc = new OrderProcessor();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = proc.processAsyncFixed(-1, error); // invalid order
        worker.join(1000);
        assertNotNull(error.get(), "Exception should have been captured by the handler");
        assertInstanceOf(IllegalArgumentException.class, error.get());
        assertTrue(error.get().getMessage().contains("-1"));
    }
}
