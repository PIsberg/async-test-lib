package com.github.asynctest.example.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A simple event aggregation service that collects events from multiple sources.
 *
 * <p><strong>THE BUG:</strong> {@code eventLog} (ArrayList) and {@code eventCounts}
 * (HashMap) are not thread-safe. When multiple threads call {@code recordEvent()}
 * concurrently, the lists and maps can become internally inconsistent:
 * <ul>
 *   <li>{@code ArrayList.add()} is not atomic — two threads can write to the same
 *       slot, or the backing array resize can lose entries.</li>
 *   <li>{@code HashMap.put()} can corrupt the internal hash table during a resize,
 *       causing an infinite loop in older JDKs or silent data loss in modern ones.</li>
 * </ul>
 *
 * <p><strong>Fix:</strong> Use {@code Collections.synchronizedList(new ArrayList<>())}
 * and {@code ConcurrentHashMap}, or redesign to collect per-thread and merge at the end.
 */
public class EventAggregatorService {

    // BUG: ArrayList and HashMap are NOT thread-safe
    private final List<String> eventLog    = new ArrayList<>();
    private final Map<String, Integer> eventCounts = new HashMap<>();

    /**
     * Record an event from a given source.
     *
     * <p>This method is called concurrently by multiple threads in production.
     * The implementation is unsafe without external synchronization.
     */
    public void recordEvent(String source, String event) {
        String entry = source + ": " + event;
        eventLog.add(entry);                              // NOT THREAD SAFE
        eventCounts.merge(source, 1, Integer::sum);       // NOT THREAD SAFE
    }

    /**
     * Return a snapshot of all recorded events.
     *
     * <p>Reading while other threads are writing to an ArrayList is also unsafe —
     * the iterator can throw {@code ConcurrentModificationException}.
     */
    public List<String> getEvents() {
        return new ArrayList<>(eventLog);
    }

    /**
     * Return per-source event counts.
     */
    public Map<String, Integer> getEventCounts() {
        return new HashMap<>(eventCounts);
    }

    public int getTotalEventCount() {
        return eventLog.size();
    }
}
