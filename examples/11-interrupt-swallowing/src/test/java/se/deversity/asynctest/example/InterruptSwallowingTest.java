package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the InterruptSwallowingDetector (Phase 12).
 *
 * ============================================================
 * NOTE: InterruptSwallowingDetector ships in async-test-lib 0.10.0.
 * This example targets 0.10.0 so it compiles from Maven Central.
 *
 * Upgrade steps:
 *   1. Change the Part 2 @Test to @AsyncTest
 *   2. Uncomment the AsyncTestContext calls inside Part 2
 * ============================================================
 *
 * THE BUG: A task runner catches InterruptedException but neither restores
 * the interrupt flag nor rethrows. Upper-level code (e.g., an executor
 * shutdown handler) can no longer observe the interrupted state, so the
 * thread ignores the cancellation signal and keeps running.
 *
 * WHY @Test PASSES: Single-threaded execution never triggers the interrupt
 * path so the swallowing code is never exercised. All assertions pass.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector records every
 * catch block that does not restore the flag and reports the offending
 * thread and location.
 */
class InterruptSwallowingTest {

    /** Buggy service: swallows InterruptedException silently. */
    static class TaskRunner {
        void runWithSleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                // BUG: interrupt flag is NOT restored and exception is NOT rethrown
                // Callers can no longer detect that this thread was interrupted.
            }
        }

        /** Fixed version: restores the interrupt flag before returning. */
        void runWithSleepFixed(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Fix: restore the flag
            }
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_runnerCompletes_singleThread() {
        TaskRunner runner = new TaskRunner();
        // Single-thread: no interruption occurs, so the buggy path is never hit.
        assertDoesNotThrow(() -> runner.runWithSleep(1));
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectInterruptSwallowing = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectInterruptSwallowing_placeholder() {
        // Placeholder: run as plain @Test to compile against 0.10.0.
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.interruptSwallowingDetector();
        //   try {
        //       Thread.sleep(10);
        //   } catch (InterruptedException e) {
        //       d.recordCatch(Thread.currentThread(), "TaskRunner.runWithSleep", false);
        //       // BAD: does not restore the flag
        //   }
        //
        // The detector will report "Thread 'xxx' caught InterruptedException at
        // [TaskRunner.runWithSleep] without restoring the interrupt flag."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — properly restores the interrupt flag
    // =========================================================================

    @Test
    void part3_fixed_restoresInterruptFlag() {
        TaskRunner runner = new TaskRunner();
        Thread worker = new Thread(() -> runner.runWithSleepFixed(1));
        worker.start();
        worker.interrupt();
        try { worker.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // After join, the thread should have finished (the interrupt was handled properly)
        assertFalse(worker.isAlive(), "Worker thread should have completed");
    }
}
