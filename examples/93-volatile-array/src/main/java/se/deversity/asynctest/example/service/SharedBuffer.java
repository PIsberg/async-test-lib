package se.deversity.asynctest.example.service;

/**
 * A shared integer buffer intended for use across multiple threads.
 *
 * <p>BUG: the backing array is declared {@code volatile} in the mistaken belief
 * that this makes individual element accesses thread-safe. The {@code volatile}
 * modifier guarantees only that the array **reference** is visible across
 * threads — not the contents. Writes to {@code buffer[i]} may remain invisible
 * to other threads indefinitely, causing stale reads and lost updates.
 */
public class SharedBuffer {

    // BUG: volatile applies to the reference only, not to individual elements.
    private volatile int[] buffer = new int[10];

    /**
     * Write {@code value} to slot {@code idx}.
     *
     * <p>BUG: this is a plain element store. Other threads may not see the
     * new value even after this method returns.
     */
    public void set(int idx, int value) {
        buffer[idx] = value; // not volatile — may be invisible to other threads
    }

    /**
     * Read the value at slot {@code idx}.
     *
     * <p>BUG: may return a stale value written by another thread.
     */
    public int get(int idx) {
        return buffer[idx]; // may observe stale data
    }

    /**
     * Expose the raw array so the detector can register it for tracking.
     */
    public int[] getBuffer() {
        return buffer;
    }

    public int capacity() {
        return buffer.length;
    }
}
