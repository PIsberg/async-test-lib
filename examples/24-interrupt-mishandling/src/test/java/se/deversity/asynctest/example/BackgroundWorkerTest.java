package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.InterruptMonitor;
import se.deversity.asynctest.example.service.BackgroundWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BackgroundWorker.
 *
 * ========================================================================
 * DETECTOR: InterruptMonitor
 * ========================================================================
 *
 * This test demonstrates a common concurrency bug in worker services where:
 * - A sequential @Test PASSES (no interruption occurs in normal execution)
 * - The same test with @AsyncTest + InterruptMonitor reveals that
 *   InterruptedException is caught and swallowed, silently breaking
 *   cooperative thread cancellation
 *
 * THE BUG:
 * BackgroundWorker.doWork() catches InterruptedException but neither
 * rethrows it nor calls Thread.currentThread().interrupt() to restore
 * the interrupted flag:
 *
 *     try {
 *         Thread.sleep(10);
 *     } catch (InterruptedException e) {
 *         // BUG: silently swallows the interrupt
 *     }
 *
 * When a graceful-shutdown handler interrupts this thread to signal
 * cancellation, the flag is cleared by the JVM when the exception is
 * thrown. Because the catch block does not restore it, the flag is gone
 * after the catch block exits. Any code that checks Thread.isInterrupted()
 * to decide whether to stop will find false and keep running indefinitely.
 *
 * WHY @Test PASSES:
 * Single-threaded tests call doWork() directly. No interruption occurs
 * during normal execution, so the buggy catch block is never reached.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * The test body explicitly interrupts the current thread before calling
 * doWork(), then records the swallowed event with InterruptMonitor via
 * recordIgnoredException(). Since Thread.currentThread().interrupt() is
 * not called inside doWork(), the monitor reports "ignored
 * InterruptedException" for every affected thread. The @AfterEach
 * assertion verifies that detection fired.
 *
 * DETECTORS TRIGGERED:
 * InterruptMonitor — Primary: InterruptedException swallowed without
 *                    restoring the interrupted flag
 *
 * FIX:
 * Add Thread.currentThread().interrupt() inside every
 * catch (InterruptedException) block, or rethrow the exception.
 */
class BackgroundWorkerTest {

    private BackgroundWorker worker;
    private InterruptMonitor interruptMonitor;
    // Guard flag so the @AfterEach assertion only runs after the @AsyncTest.
    private volatile boolean runningAsyncTest = false;

    @BeforeEach
    void setUp() {
        worker = new BackgroundWorker();
        interruptMonitor = new InterruptMonitor();
    }

    /**
     * After the @AsyncTest run completes, verify the monitor detected
     * swallowed interrupts. @AfterEach runs once after all threads and
     * invocations finish.
     */
    @AfterEach
    void verifyInterruptMishandlingDetected() {
        if (!runningAsyncTest) {
            return;
        }
        InterruptMonitor.InterruptReport report = interruptMonitor.analyzeInterruptHandling();
        assertTrue(report.hasIssues(),
                "InterruptMonitor should have flagged the swallowed InterruptedException.\n" + report);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no interruption in normal execution
    // -------------------------------------------------------------------------

    @Test
    void testDoWork_singleThread_completesNormally() {
        // Normal execution: no interruption, doWork() returns after sleep
        worker.doWork();
        assertEquals(1, worker.getWorkCount());
    }

    @Test
    void testShutdown_preventsWork() {
        worker.shutdown();
        worker.doWork();
        // After shutdown, running flag is false so doWork() returns immediately
        assertEquals(0, worker.getWorkCount());
    }

    @Test
    void testDoWork_singleThread_multipleCalls() {
        worker.doWork();
        worker.doWork();
        worker.doWork();
        assertEquals(3, worker.getWorkCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes swallowed InterruptedException
    // -------------------------------------------------------------------------

    /**
     * The bug: when this thread is interrupted before doWork() calls
     * Thread.sleep(), sleep() throws InterruptedException immediately.
     * The catch block in doWork() swallows it without restoring the flag.
     * recordIgnoredException() records the swallowed interrupt so the
     * @AfterEach assertion can verify that detection fired.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — @AfterEach will assert that InterruptMonitor
     *    flagged threads that swallowed InterruptedException
     * 3. Fix: add Thread.currentThread().interrupt() inside the catch
     *    block in BackgroundWorker.doWork()
     */
    @Disabled("Remove @Disabled to see interrupt swallowing detected by InterruptMonitor")
    @AsyncTest(threads = 6, invocations = 10, detectAll = false, detectInterruptMishandling = true, failOn = FailOn.LOW)
    void testDoWork_concurrent_detectsInterruptSwallowing() {
        runningAsyncTest = true;

        // Interrupt this thread so that Thread.sleep() inside doWork()
        // throws InterruptedException immediately.
        Thread.currentThread().interrupt();

        // doWork() catches InterruptedException but does NOT restore the flag.
        worker.doWork();

        // At this point Thread.isInterrupted() is false — the interrupt was swallowed.
        // Record the ignored exception so the monitor can report it.
        interruptMonitor.recordIgnoredException(
                "BackgroundWorker.doWork — sleep interrupted but flag not restored");

        // After the @AsyncTest run, @AfterEach calls analyzeInterruptHandling()
        // and asserts: "Thread 'xxx': ignored InterruptedException - BackgroundWorker.doWork"
    }

    /**
     * Fixed version: doWorkFixed() calls Thread.currentThread().interrupt()
     * inside the catch block. After the call, isInterrupted() is true,
     * and shutdown handlers can observe the cancellation request.
     */
    @Test
    void testDoWorkFixed_restoresInterruptFlag() throws InterruptedException {
        BackgroundWorker fixedWorker = new BackgroundWorker();
        Thread t = new Thread(() -> {
            fixedWorker.doWorkFixed();
            // After doWorkFixed(), the interrupt flag must still be set
            assertTrue(Thread.currentThread().isInterrupted(),
                    "doWorkFixed() must restore the interrupted flag");
        });
        t.start();
        t.interrupt(); // signal cancellation
        t.join(500);
        assertFalse(t.isAlive(), "Worker thread should have completed");
    }
}
