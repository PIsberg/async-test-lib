package se.deversity.asynctest.example.service;

import java.util.concurrent.locks.StampedLock;

/**
 * An inventory service that uses StampedLock optimistic reads without validating
 * the stamp before consuming the read data.
 *
 * BUG: {@link #getStock()} calls {@code lock.tryOptimisticRead()} but never
 * calls {@code lock.validate(stamp)}. A concurrent {@link #addStock(int)} can
 * write between the optimistic read and the return, producing an inconsistent value.
 */
public class InventoryService {

    // Exposed for the detector API calls in the test
    public final StampedLock lock = new StampedLock();

    private int stock = 100;

    /**
     * Returns current stock level using an optimistic read.
     *
     * BUG: validate(stamp) is never called — torn reads are silently returned.
     */
    public int getStock() {
        long stamp = lock.tryOptimisticRead();
        int currentStock = this.stock; // read without validation — may be torn
        // BUG: missing: if (!lock.validate(stamp)) { ... fallback ... }
        return currentStock;
    }

    /**
     * Adds {@code n} units to stock under a write lock.
     */
    public void addStock(int n) {
        long stamp = lock.writeLock();
        try {
            stock += n;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Reduces stock by {@code n} under a write lock.
     */
    public boolean removeStock(int n) {
        long stamp = lock.writeLock();
        try {
            if (stock < n) return false;
            stock -= n;
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
