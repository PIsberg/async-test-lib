package com.example.agentfixture;

import java.util.concurrent.locks.StampedLock;

/**
 * The {@code StampedLock} class javadoc's {@code Point}: moves under the write lock, and an
 * optimistic read that validates and falls back to the read lock, next to one that never
 * validates (#740).
 */
public class OptimisticPointBean {

    private final StampedLock sl = new StampedLock();

    private double x;

    private double y;

    /** Moves the point under the write lock. @param deltaX the x step @param deltaY the y step */
    public void move(double deltaX, double deltaY) {
        long stamp = sl.writeLock();
        try {
            x += deltaX;
            y += deltaY;
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    /** {@return the distance, read optimistically and validated, re-read under the read lock} */
    public double distanceFromOrigin() {
        long stamp = sl.tryOptimisticRead();
        double currentX = x;
        double currentY = y;
        if (!sl.validate(stamp)) {
            stamp = sl.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                sl.unlockRead(stamp);
            }
        }
        return Math.hypot(currentX, currentY);
    }

    /** {@return the distance, read under an optimistic stamp that is never validated, the bug} */
    public double distanceWithoutValidating() {
        sl.tryOptimisticRead();
        double currentX = x;
        double currentY = y;
        return Math.hypot(currentX, currentY);
    }
}
