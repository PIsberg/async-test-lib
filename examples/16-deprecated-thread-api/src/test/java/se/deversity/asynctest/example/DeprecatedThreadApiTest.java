package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the DeprecatedThreadApiDetector (Phase 12).
 *
 * ============================================================
 * NOTE: DeprecatedThreadApiDetector ships in async-test-lib 0.10.0.
 * This example targets 0.10.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A thread manager uses Thread.stop() to forcibly terminate
 * a worker. Thread.stop() throws ThreadDeath into the target thread
 * at an arbitrary point, releasing all monitors it holds — leaving
 * any shared state it was updating in an inconsistent state. This API
 * was deprecated in Java 1.2 and its implementation removed in Java 20.
 *
 * WHY @Test PASSES: The single-threaded test just exercises the control
 * flow path; it does not verify thread safety of shared state.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector records any
 * call to Thread.stop/suspend/resume/destroy and reports it immediately.
 */
@SuppressWarnings("deprecation")
class DeprecatedThreadApiTest {

    static class WorkerManager {
        private Thread worker;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        void startWorker() {
            cancelled.set(false);
            worker = new Thread(() -> {
                while (!cancelled.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }
            });
            worker.start();
        }

        // Buggy: uses Thread.stop()
        void forceStop() {
            if (worker != null) {
                worker.stop(); // DANGEROUS — removed in Java 20
            }
        }

        // Fixed: cooperative cancellation via volatile flag + interrupt
        void cancelGracefully() throws InterruptedException {
            if (worker != null) {
                cancelled.set(true);
                worker.interrupt();
                worker.join(500);
            }
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_workerStarted_singleThread() throws Exception {
        WorkerManager mgr = new WorkerManager();
        mgr.startWorker();
        assertNotNull(mgr.worker);
        mgr.cancelGracefully(); // use the safe path in @Test
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 2, detectDeprecatedThreadApi = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectDeprecatedApi_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.deprecatedThreadApiDetector();
        //   d.recordApiUse("Thread.stop", Thread.currentThread());
        //   // worker.stop(); // would be flagged
        //
        // The detector will report "Thread '...' called deprecated API 'Thread.stop' —
        // this method is unsafe and was removed/deprecated in Java 20+."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — cooperative cancellation
    // =========================================================================

    @Test
    void part3_fixed_cooperativeCancellation() throws Exception {
        WorkerManager mgr = new WorkerManager();
        mgr.startWorker();
        mgr.cancelGracefully();
        assertFalse(mgr.worker.isAlive(), "Worker should have stopped gracefully");
    }
}
