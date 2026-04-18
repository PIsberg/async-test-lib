package com.github.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * A request counter that tracks per-endpoint hit counts.
 *
 * BUG: The entire map is guarded by a single coarse-grained lock.
 * Under load, every thread blocks waiting for this one lock,
 * even when they're updating completely different endpoints.
 *
 * LockContentionDetector flags this as a hot-lock hotspot.
 *
 * FIX: Use ConcurrentHashMap.merge() for lock-free per-key updates,
 * or LongAdder per entry for high-throughput counters.
 */
public class RequestCounterService {

    private final Map<String, Long> counts = new HashMap<>();
    private final Object lock = new Object();

    public void recordRequest(String endpoint) {
        synchronized (lock) {
            counts.merge(endpoint, 1L, Long::sum);
        }
    }

    public long getCount(String endpoint) {
        synchronized (lock) {
            return counts.getOrDefault(endpoint, 0L);
        }
    }

    public Map<String, Long> snapshot() {
        synchronized (lock) {
            return new HashMap<>(counts);
        }
    }
}
