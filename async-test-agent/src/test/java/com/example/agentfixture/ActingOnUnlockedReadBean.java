package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads a lock-guarded field without the lock and then acts on what it read.
 *
 * <p>The negative twin of {@link RevalidatingHintBean}, identical except that the value the
 * unlocked read returned is the decision: nothing reads the field again once the lock is held, so
 * two threads can both see the old value and both resize. That is a lost update rather than a
 * wasted branch.
 *
 * <p>If a rule that lets the hint shape go quiet also quietens this, the rule is wrong.
 */
public class ActingOnUnlockedReadBean {

    private final ReentrantLock lock = new ReentrantLock();

    private int threshold = 4;
    private int size;

    /** Adds one entry, resizing on the strength of the unlocked read. */
    public void put() {
        // The decision. Nothing re-reads threshold or size under the lock, so a stale pair is
        // acted on and two threads can resize on the same observation.
        boolean needsResize = size >= threshold;

        lock.lock();
        try {
            if (needsResize) {
                threshold = threshold * 2;
            }
            size = size + 1;
        } finally {
            lock.unlock();
        }
    }

    /** {@return the current threshold, under the lock} */
    public int threshold() {
        lock.lock();
        try {
            return threshold;
        } finally {
            lock.unlock();
        }
    }
}
