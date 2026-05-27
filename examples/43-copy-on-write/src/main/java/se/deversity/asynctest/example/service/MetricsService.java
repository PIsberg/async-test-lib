package se.deversity.asynctest.example.service;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BUGGY service that demonstrates CopyOnWriteArrayList misuse.
 *
 * BUG: CopyOnWriteArrayList is used in a write-heavy scenario.
 *      Every add() copies the entire backing array — O(n) per write.
 *      With many concurrent writers the list grows large and each write
 *      becomes increasingly expensive, causing CPU spikes and GC pressure.
 *
 * FIX: Replace CopyOnWriteArrayList with a write-optimised alternative:
 *      - ConcurrentLinkedQueue for an unbounded lock-free append queue, or
 *      - LongAdder if only the count is needed (allocation-free hot path).
 */
public class MetricsService {

    // BUG: CopyOnWriteArrayList is write-heavy — wrong collection for hot path
    private final CopyOnWriteArrayList<Long> timestamps = new CopyOnWriteArrayList<>();

    /**
     * Record a new event timestamp. Called on every request.
     * BUG: triggers an O(n) array copy on every call.
     */
    public void recordEvent() {
        timestamps.add(System.currentTimeMillis()); // BUG: O(n) copy per call
    }

    public int getEventCount() {
        return timestamps.size();
    }

    public CopyOnWriteArrayList<Long> getTimestamps() {
        return timestamps;
    }
}
