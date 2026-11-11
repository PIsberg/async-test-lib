package com.example.agentfixture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Signals a {@code Condition}, then awaits it, behind an {@code if} or inside a loop (#1255).
 *
 * <p>The {@code Condition} form of {@link IfWaitHandOffBean} and {@link LoopWaitHandOffBean}. The
 * first {@code signalAll} of a round finds nobody waiting and is discarded, so the last thread to
 * await in the round waits for a signal that already came and went, and only the timeout lets it
 * out. Nothing here is declared to the library: the agent substitutes the {@code await} and the
 * {@code signalAll}, and the loop is the backward jump it marks around the await.
 */
public class ConditionSignalHandOffBean {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private boolean handedOff;

    /**
     * Signals whoever is waiting, then awaits a signal of its own behind an {@code if}.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void signalThenAwaitOnce() throws InterruptedException {
        signal();
        lock.lock();
        try {
            if (!handedOff) {
                ready.await(20, TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals whoever is waiting, then awaits inside a bounded loop that re-tests the state.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void signalThenAwaitInALoop() throws InterruptedException {
        signal();
        lock.lock();
        try {
            int polls = 0;
            while (!handedOff && polls++ < 2) {
                ready.await(10, TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
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
