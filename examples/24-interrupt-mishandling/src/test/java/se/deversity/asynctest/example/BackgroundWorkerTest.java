package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.InterruptMonitor;
import se.deversity.asynctest.example.service.BackgroundWorker;
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
 * The test body interrupts the current thread before calling doWork(), so
 * Thread.sleep() throws immediately. BackgroundWorker.observeInterrupts wires
 * recordInterruptException and recordInterruptRestored into the catch blocks,
 * and the monitor records what it finds there: sleep() has already cleared the
 * flag, and doWork() never puts it back, so the event is stored as caught and
 * not restored. The report names BackgroundWorker.doWork and the line, and
 * failOn = FailOn.LOW turns it into a failed run.
 *
 * DETECTOR ENABLED HERE:
 * InterruptMonitor — InterruptedException swallowed without restoring the
 * interrupted flag. It is the only one this demonstration switches on, so it is
 * the only one that can report.
 *
 * FIX:
 * Add Thread.currentThread().interrupt() inside every
 * catch (InterruptedException) block, or rethrow the exception.
 */
class BackgroundWorkerTest {

    private BackgroundWorker worker;

    @BeforeEach
    void setUp() {
        worker = new BackgroundWorker();
        Thread.interrupted();   // clear any flag a previous test left on this thread
    }

    /**
     * Pins the monitor's positive direction without needing the concurrent run: an
     * InterruptedException caught with the flag left cleared is the bug, and the monitor must
     * say so.
     */
    @Test
    void testInterruptMonitor_swallowedInterrupt_reports() {
        InterruptMonitor monitor = new InterruptMonitor();
        BackgroundWorker swallower = new BackgroundWorker();
        swallower.observeInterrupts(
                monitor::recordInterruptException, monitor::recordInterruptRestored);

        Thread.currentThread().interrupt();
        swallower.doWork();
        Thread.interrupted();

        assertTrue(monitor.analyzeInterruptHandling().hasIssues(),
                "an interrupt caught and not restored is the bug this monitor exists for");
    }

    /**
     * And the other direction: the catch-and-restore idiom is the recommended fix, so a monitor
     * that reported it would be arguing against its own advice.
     */
    @Test
    void testInterruptMonitor_restoredInterrupt_isSilent() {
        InterruptMonitor monitor = new InterruptMonitor();
        BackgroundWorker restorer = new BackgroundWorker();
        restorer.observeInterrupts(
                monitor::recordInterruptException, monitor::recordInterruptRestored);

        Thread.currentThread().interrupt();
        restorer.doWorkFixed();
        Thread.interrupted();

        assertFalse(monitor.analyzeInterruptHandling().hasIssues(),
                "restoring the flag inside the catch block is the fix, not a finding");
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
     * The hook inside the catch block records it, and because the flag is
     * already gone by then the monitor stores it as ignored.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with, for every worker thread,
     *      interrupt caught but not restored at BackgroundWorker.doWork(BackgroundWorker.java:54)
     * 3. Fix: add Thread.currentThread().interrupt() inside the catch
     *    block in BackgroundWorker.doWork()
     */
    @Disabled("Remove @Disabled to see interrupt swallowing detected by InterruptMonitor")
    @AsyncTest(threads = 6, invocations = 10, detectAll = false, detectInterruptMishandling = true, failOn = FailOn.LOW)
    void testDoWork_concurrent_detectsInterruptSwallowing() {
        // The monitor has to be the one the run owns. This demonstration used to record into a
        // locally constructed InterruptMonitor and assert on it from @AfterEach; the library
        // never reads that instance, so failOn had nothing to gate on and enabling the test left
        // it green. See issue #346.
        InterruptMonitor monitor = AsyncTestContext.interruptMonitor();
        worker.observeInterrupts(
                monitor::recordInterruptException, monitor::recordInterruptRestored);

        // Interrupt this thread so Thread.sleep() inside doWork() throws immediately.
        Thread.currentThread().interrupt();

        // BUG: doWork() catches InterruptedException and does not restore the flag. The hook
        // fires inside the catch block, where isInterrupted() is already false because sleep()
        // cleared it, so the monitor records the interrupt as caught and not restored.
        worker.doWork();

        // Leave the thread clean for whatever the runner does with it next.
        Thread.interrupted();
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
