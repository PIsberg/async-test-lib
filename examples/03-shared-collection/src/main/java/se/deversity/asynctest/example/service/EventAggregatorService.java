package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A simple event aggregation service that collects events from multiple sources.
 *
 * <p><strong>THE BUG:</strong> {@code eventLog} (ArrayList) and {@code eventCounts}
 * (HashMap) are not thread-safe. When multiple threads call {@code recordEvent()}
 * concurrently, the lists and maps can become internally inconsistent:
 * <ul>
 *   <li>{@code ArrayList.add()} is not atomic - two threads can write to the same
 *       slot, or the backing array resize can lose entries.</li>
 *   <li>{@code HashMap.put()} can corrupt the internal hash table during a resize,
 *       causing an infinite loop in older JDKs or silent data loss in modern ones.</li>
 * </ul>
 *
 * <p><strong>Fix:</strong> Use {@code Collections.synchronizedList(new ArrayList<>())}
 * and {@code ConcurrentHashMap}, or redesign to collect per-thread and merge at the end.
 *
 * <p><strong>INSTRUMENTATION:</strong> {@code SharedCollectionDetector} is recording-fed and
 * keys every access on {@code System.identityHashCode} of the collection, so it can only see
 * sharing if it is handed the <em>same instance</em> the threads are mutating.
 * {@link #getEvents()} returns a defensive copy - a different instance on every call - so
 * recording against that showed the detector eight collections with one writer each instead of
 * one collection with eight, and it reported nothing.
 * {@link #observeCollectionWrites(BiConsumer)} is the seam that hands over the live instance at
 * the moment it is mutated, from the thread that mutates it. It defaults to a no-op and the
 * production path never touches the test library: this is the seam, not the bug.
 */
public class EventAggregatorService {

    // BUG: ArrayList and HashMap are NOT thread-safe
    private final List<String> eventLog    = new ArrayList<>();
    private final Map<String, Integer> eventCounts = new HashMap<>();

    /** Called with the live collection and the operation name after each mutation. */
    private volatile BiConsumer<Object, String> onCollectionWrite = (collection, operation) -> { };

    /**
     * Record an event from a given source.
     *
     * <p>This method is called concurrently by multiple threads in production.
     * The implementation is unsafe without external synchronization.
     *
     * @param source the event source identifier
     * @param event  the event name
     */
    public void recordEvent(String source, String event) {
        String entry = source + ": " + event;
        eventLog.add(entry);                              // NOT THREAD SAFE
        onCollectionWrite.accept(eventLog, "add");
        eventCounts.merge(source, 1, Integer::sum);       // NOT THREAD SAFE
        onCollectionWrite.accept(eventCounts, "merge");
    }

    /**
     * Installs the hook the detector needs. A no-op by default, so production behaviour is
     * unchanged whether or not a test is watching.
     *
     * @param onWrite called with the live collection and a short operation name after each write
     */
    public void observeCollectionWrites(BiConsumer<Object, String> onWrite) {
        this.onCollectionWrite = onWrite;
    }

    /**
     * Return a snapshot of all recorded events.
     *
     * <p>Reading while other threads are writing to an ArrayList is also unsafe -
     * the iterator can throw {@code ConcurrentModificationException}.
     *
     * @return a defensive copy of the event log, a fresh list on every call
     */
    public List<String> getEvents() {
        return new ArrayList<>(eventLog);
    }

    /**
     * Return per-source event counts.
     *
     * @return a defensive copy of the per-source counts
     */
    public Map<String, Integer> getEventCounts() {
        return new HashMap<>(eventCounts);
    }

    /**
     * {@return the number of entries currently in the event log}
     */
    public int getTotalEventCount() {
        return eventLog.size();
    }
}
