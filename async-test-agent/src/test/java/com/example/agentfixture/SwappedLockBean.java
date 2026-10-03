package com.example.agentfixture;

/**
 * A {@code synchronized} block on a non-final instance field that one instance reassigns (#793): two
 * threads that read the field either side of {@link #swapLock()} hold different monitors, so the
 * block excludes nothing between them. The detector sees it only if the agent says which instance
 * each monitor was read from.
 */
public class SwappedLockBean {

    private Object lock = new Object();

    private int count;

    /** Replaces the monitor, the defect. */
    public void swapLock() {
        lock = new Object();
    }

    /** Increments under whatever monitor the field holds now. */
    public void increment() {
        synchronized (lock) {
            count++;
        }
    }

    /** {@return the count, read under the current monitor} */
    public int count() {
        synchronized (lock) {
            return count;
        }
    }
}
