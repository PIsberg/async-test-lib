package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A background worker that performs periodic work in a loop and supports
 * graceful shutdown via thread interruption.
 *
 * BUG: doWork() calls Thread.sleep() inside a try-catch block. When
 * InterruptedException is caught, the handler logs the event but silently
 * discards the interrupt signal without calling Thread.currentThread().interrupt().
 *
 * Because the interrupted flag is cleared by the catch block and never
 * restored, any caller that tries to stop this worker by interrupting the
 * thread will find the flag already gone when it checks. Cooperative
 * cancellation — the standard Java mechanism for stopping threads — silently
 * fails. The worker keeps running indefinitely even after a shutdown signal.
 *
 * InterruptMonitor flags the caught InterruptedException as "caught but not
 * restored" and reports the thread and location.
 *
 * FIX: In every catch (InterruptedException) block, either rethrow the
 * exception or call Thread.currentThread().interrupt() to restore the flag
 * so callers higher up the stack can observe the cancellation request.
 */
public class BackgroundWorker {

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger workCount = new AtomicInteger(0);

    /**
     * Performs one unit of work with a short sleep between iterations.
     *
     * BUG: The InterruptedException catch block swallows the interrupt.
     * After this method returns, Thread.isInterrupted() is false even
     * though the thread was interrupted — the cancellation signal is lost.
     */
    public void doWork() {
        if (!running.get()) {
            return;
        }
        try {
            Thread.sleep(10); // simulates I/O wait or periodic delay
            workCount.incrementAndGet();
        } catch (InterruptedException e) {
            // BUG: interrupt flag is NOT restored.
            // Callers that interrupt this thread to request shutdown will
            // find the flag already gone after this catch block exits.
            // Thread.currentThread().interrupt() is intentionally missing.
        }
    }

    /**
     * Fixed version: restores the interrupt flag before returning.
     * After this method returns, Thread.isInterrupted() reflects the
     * original interruption so callers can act on it.
     */
    public void doWorkFixed() {
        if (!running.get()) {
            return;
        }
        try {
            Thread.sleep(10);
            workCount.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // ✅ restore the interrupted flag
        }
    }

    /**
     * Requests the worker to stop.
     */
    public void shutdown() {
        running.set(false);
    }

    /**
     * Returns the number of work units completed.
     */
    public int getWorkCount() {
        return workCount.get();
    }
}
