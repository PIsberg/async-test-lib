package com.example.agentfixture;

/**
 * Two monitors nested by plain {@code synchronized} blocks and methods, the more common way to
 * write a lock-order inversion than two {@code ReentrantLock}s (#1243). Woven with
 * {@code fields=true}, each {@code MONITORENTER} and synchronized-method entry reaches
 * {@code LockOrderValidator}.
 */
public class MonitorOrderBean {

    private final Object alpha = new Object();
    private final Object beta = new Object();
    private final Object gate = new Object();

    private int work;

    /** {@code alpha} then {@code beta}. */
    public void forward() {
        synchronized (alpha) {
            synchronized (beta) {
                work++;
            }
        }
    }

    /** {@code beta} then {@code alpha}: the inversion of {@link #forward()}. */
    public void reverse() {
        synchronized (beta) {
            synchronized (alpha) {
                work++;
            }
        }
    }

    /** {@link #forward()} under {@code gate}: cannot deadlock with {@link #gatedReverse()}. */
    public void gatedForward() {
        synchronized (gate) {
            forward();
        }
    }

    /** {@link #reverse()} under {@code gate}. */
    public void gatedReverse() {
        synchronized (gate) {
            reverse();
        }
    }

    /**
     * Holds this bean's monitor through a synchronized method, re-enters it through another, and
     * takes {@code alpha} after the inner method returned: the bean is still held once (#1244).
     */
    public synchronized void outerThenAlpha() {
        inner();
        synchronized (alpha) {
            work++;
        }
    }

    private synchronized void inner() {
        work++;
    }

    /** {@code alpha} then this bean's monitor: the inversion of {@link #outerThenAlpha()}. */
    public void alphaThenOuter() {
        synchronized (alpha) {
            inner();
        }
    }

    public int work() {
        return work;
    }
}
