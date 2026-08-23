package com.example.agentfixture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Correct code guarded by a {@link ReentrantLock} rather than by a monitor.
 *
 * <p>Uses a LinkedHashMap so the report, which is labelled by collection type, distinguishes this
 * subject from the monitor-guarded twin.
 *
 * <p>The shape that matters because it is how most modern concurrent code is written: no
 * {@code synchronized} keyword appears, so there is no {@code MONITORENTER} to weave, and until the
 * lock hooks existed every access here read as unguarded. A finding on this class is a false
 * positive on correct code.
 */
public class LockGuardedStateBean {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Integer> entries = new LinkedHashMap<>();

    /** Reads and writes the shared map under the lock. @param key the key */
    public void record(String key) {
        lock.lock();
        try {
            Integer previous = entries.get(key);
            entries.put(key, previous == null ? 1 : previous + 1);
        } finally {
            lock.unlock();
        }
    }

    /** @return how many distinct keys survived the run */
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }
}
