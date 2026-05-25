package se.deversity.asynctest.example.service;

import java.util.concurrent.locks.StampedLock;

/**
 * BUGGY service that demonstrates unreleased StampedLock write stamp.
 *
 * BUG: moveTo() acquires the write lock but never calls unlockWrite(stamp).
 *      The first call leaves the lock permanently held. All subsequent callers
 *      of moveTo() block indefinitely on writeLock(), causing a live deadlock.
 *
 * FIX: always release the stamp in a finally block:
 *
 * <pre>{@code
 * long stamp = lock.writeLock();
 * try {
 *     this.x = nx;
 *     this.y = ny;
 * } finally {
 *     lock.unlockWrite(stamp);
 * }
 * }</pre>
 */
public class PositionTracker {

    private final StampedLock lock = new StampedLock();
    private double x;
    private double y;

    /**
     * Update position coordinates.
     * BUG: acquires write lock but never releases the stamp.
     */
    public void moveTo(double nx, double ny) {
        long stamp = lock.writeLock();
        // BUG: no finally block — stamp is never released.
        this.x = nx;
        this.y = ny;
        // lock.unlockWrite(stamp) is missing here!
    }

    /** Read current X coordinate (uses optimistic read). */
    public double getX() {
        long stamp = lock.tryOptimisticRead();
        double value = x;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { value = x; } finally { lock.unlockRead(stamp); }
        }
        return value;
    }

    /** Read current Y coordinate (uses optimistic read). */
    public double getY() {
        long stamp = lock.tryOptimisticRead();
        double value = y;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { value = y; } finally { lock.unlockRead(stamp); }
        }
        return value;
    }

    public StampedLock getLock() {
        return lock;
    }
}
