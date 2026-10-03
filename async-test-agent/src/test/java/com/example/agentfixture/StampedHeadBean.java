package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * The head of {@link AbaStackBean}'s stack held in an {@code AtomicStampedReference} (#817), every
 * call site here so the weaver substitutes each one. The stamp is the defence against an A-B-A: a
 * caller that bumps it on every store makes a stale compare-and-set fail; one that reuses it has the
 * same A-B-A as a bare reference.
 */
public class StampedHeadBean {

    private final AtomicStampedReference<AbaStackBean.Node> head;

    public StampedHeadBean(AbaStackBean.Node initial) {
        head = new AtomicStampedReference<>(initial, 0);
    }

    /** {@return the head and, in {@code stamp[0]}, its stamp, read together} */
    public AbaStackBean.Node read(int[] stamp) {
        return head.get(stamp);
    }

    /** Stores {@code node} with {@code stamp}. */
    public void store(AbaStackBean.Node node, int stamp) {
        head.set(node, stamp);
    }

    /** {@return the stamp now} */
    public int stamp() {
        int[] stamp = new int[1];
        head.get(stamp);
        return stamp[0];
    }

    /** {@return whether the head was {@code expected} with {@code stamp} and is now {@code update}} */
    public boolean swap(AbaStackBean.Node expected, AbaStackBean.Node update, int stamp) {
        return head.compareAndSet(expected, update, stamp, stamp + 1);
    }
}
