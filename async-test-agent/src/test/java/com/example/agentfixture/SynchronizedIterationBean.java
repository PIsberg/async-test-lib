package com.example.agentfixture;

import java.util.Collection;

/**
 * Iterates collections it is handed, recording nothing. Woven with {@code collections=true}, the
 * for-each loops' {@code iterator()} calls reach the iteration hook; the collections themselves are
 * built by the caller, in code the agent does not weave, so the run never sees where they came from.
 */
public class SynchronizedIterationBean {

    /**
     * Iterates {@code collection} holding no lock.
     *
     * @param collection what to iterate
     * @return how many elements it had
     */
    public int iterate(Collection<?> collection) {
        int count = 0;
        for (Object ignored : collection) {
            count++;
        }
        return count;
    }

    /**
     * Iterates {@code collection} inside {@code synchronized (lock)}.
     *
     * @param lock       the monitor to hold
     * @param collection what to iterate
     * @return how many elements it had
     */
    public int iterateHolding(Object lock, Collection<?> collection) {
        synchronized (lock) {
            return iterate(collection);
        }
    }
}
