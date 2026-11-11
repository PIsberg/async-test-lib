package com.example.agentfixture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@code Condition.await} that lets its lock go while another lock stays held (#1256): taking
 * the condition's lock back when the wait ends nests it inside the other one.
 *
 * <p>{@link #awaitInsideOther()} takes {@code outer} then {@code inner} and awaits on
 * {@code outer}'s condition: on the way back {@code outer} is taken again while {@code inner} is
 * held, the reverse of the way in. Two threads doing it can deadlock: one waits to take
 * {@code outer} back while holding {@code inner}, the other holds {@code outer} and waits for
 * {@code inner}. {@link #awaitInnermost()} takes them in the same order but awaits on
 * {@code inner}'s condition, so the lock it takes back is the innermost one and the order stays
 * the same.
 *
 * <p>Nobody signals: each await runs out its one-millisecond timeout.
 */
public class ConditionOrderBean {

    private final ReentrantLock outer = new ReentrantLock();
    private final ReentrantLock inner = new ReentrantLock();
    private final Condition outerReady = outer.newCondition();
    private final Condition innerReady = inner.newCondition();

    /** Takes {@code outer}, then {@code inner}, then awaits {@code outer}'s condition. */
    public void awaitInsideOther() {
        outer.lock();
        try {
            inner.lock();
            try {
                outerReady.await(1, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inner.unlock();
            }
        } finally {
            outer.unlock();
        }
    }

    /** Takes {@code outer}, then {@code inner}, then awaits {@code inner}'s condition. */
    public void awaitInnermost() {
        outer.lock();
        try {
            inner.lock();
            try {
                innerReady.awaitNanos(TimeUnit.MILLISECONDS.toNanos(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inner.unlock();
            }
        } finally {
            outer.unlock();
        }
    }
}
