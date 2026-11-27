package com.example.agentfixture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Predicate loops that leave without reaching their back-edge, each after a signal nobody was
 * waiting for (#1501): a timed {@code Condition} loop that returns when its await times out, and
 * {@code while (!ready) wait()} and {@code await()} loops ended by an interrupt. All three re-test
 * the state after every wakeup, so a lost signal cannot strand them. Nothing here is declared to
 * the library.
 */
public class HandledWaitExitBean {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final Object monitor = new Object();
    private boolean done;

    /** {@code while (!done) { if (!ready.await(..)) return; }}: the timeout is the way out. */
    public void signalThenReturnOnTimeout() throws InterruptedException {
        signal();
        lock.lock();
        try {
            while (!done) {
                if (!ready.await(5, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@code while (!done) ready.await()}, cancelled by an interrupt before it is signalled. */
    public void signalThenInterruptedAwait() {
        signal();
        lock.lock();
        try {
            Thread.currentThread().interrupt();
            while (!done) {
                ready.await();
            }
        } catch (InterruptedException cancelled) {
            // cancelled: the loop is left, the flag was consumed by the throw
        } finally {
            lock.unlock();
        }
    }

    /** {@code while (!done) monitor.wait()}, cancelled by an interrupt before it is notified. */
    public void notifyThenInterruptedWait() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
        synchronized (monitor) {
            Thread.currentThread().interrupt();
            try {
                while (!done) {
                    monitor.wait();
                }
            } catch (InterruptedException cancelled) {
                // cancelled: the loop is left, the flag was consumed by the throw
            }
        }
    }

    /**
     * {@code while (!done) { monitor.wait(t); if (late) return; }}: the deadline ends the loop
     * without its back-edge, after every wakeup re-tested in the loop's own way (#1551).
     */
    public void notifyThenDeadlineLoop() throws InterruptedException {
        synchronized (monitor) {
            monitor.notifyAll();
        }
        long deadline = System.nanoTime() + 5_000_000L;
        synchronized (monitor) {
            while (!done) {
                monitor.wait(2);
                if (System.nanoTime() - deadline > 0) {
                    return;
                }
            }
        }
    }

    private void signal() {
        lock.lock();
        try {
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
