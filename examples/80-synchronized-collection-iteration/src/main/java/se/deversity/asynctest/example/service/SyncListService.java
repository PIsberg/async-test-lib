package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BUGGY service that demonstrates unsafe iteration of a synchronized list.
 *
 * BUG: printAll() iterates the synchronized wrapper without holding the
 *      wrapper's intrinsic lock. Each iterator.next() acquires and releases the
 *      lock independently — a concurrent add() between two next() calls
 *      increments modCount and throws ConcurrentModificationException.
 *
 * FIX: wrap the iteration block in synchronized(items) { ... }:
 *
 * <pre>{@code
 * synchronized (items) {
 *     for (String item : items) {
 *         System.out.println(item);
 *     }
 * }
 * }</pre>
 */
public class SyncListService {

    // Collections.synchronizedList synchronizes individual operations,
    // but NOT iteration — the caller must do that manually.
    private final List<String> items = Collections.synchronizedList(new ArrayList<>());

    /** Add an item (individually synchronized — safe). */
    public void add(String item) {
        items.add(item);
    }

    /**
     * Print all items by iterating the list.
     * BUG: iterates without holding the wrapper's lock — unsafe under concurrency.
     */
    public List<String> snapshot() {
        List<String> copy = new ArrayList<>();
        // BUG: for-each uses the iterator without external synchronization.
        for (String item : items) {
            copy.add(item);
        }
        return copy;
    }

    public int size() {
        return items.size();
    }

    /** Expose the wrapper so the test can register it with the detector. */
    public List<String> getItems() {
        return items;
    }
}
