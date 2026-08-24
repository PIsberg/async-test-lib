package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads a lock-guarded field without the lock, then re-reads it under the lock before acting.
 *
 * <p>The shape Spring's {@code ConcurrentReferenceHashMap$Segment} uses for {@code resizeThreshold}
 * and that concurrent collections use for size and capacity hints generally: the unlocked read
 * decides only whether the expensive path is worth entering, and the value it returned is thrown
 * away. The decision is made again inside the lock, against a value that cannot change under it.
 *
 * <p>The unlocked read is a real data race on a non-volatile field and the lockset is right to see
 * one. What makes the class correct is that nothing acts on what it returned, which is what
 * {@link ActingOnUnlockedReadBean} is here to contrast: the two differ only in the re-read.
 */
public class RevalidatingHintBean {

    private final ReentrantLock lock = new ReentrantLock();

    private int threshold = 4;
    private int size;

    /** Adds one entry, resizing when the threshold is reached. */
    public void put() {
        // Hint only. A stale value here costs an unnecessary trip through the resize branch, and
        // the value itself is not used for anything else.
        boolean mayNeedResize = size >= threshold;

        lock.lock();
        try {
            if (mayNeedResize && size >= threshold) {
                // The read that decides. Every write to threshold is under this lock, so this
                // value cannot change while the decision is being made on it.
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
