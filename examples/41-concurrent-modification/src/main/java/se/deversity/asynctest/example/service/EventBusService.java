package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * BUGGY service that demonstrates concurrent modification of an ArrayList.
 *
 * BUG: fireEvent() iterates the listeners list while register() may add to
 *      the same list from another thread. The ArrayList iterator checks
 *      modCount and throws ConcurrentModificationException when it detects
 *      a structural change mid-iteration.
 *
 * FIX: guard both methods with synchronized(listeners), or use
 *      CopyOnWriteArrayList for the listeners field.
 */
public class EventBusService {

    // BUG: ArrayList is not thread-safe for concurrent iteration + modification
    private final List<Runnable> listeners = new ArrayList<>();

    /** Register a listener. Not thread-safe when fireEvent() is concurrent. */
    public void register(Runnable listener) {
        listeners.add(listener);  // BUG: structural modification without sync
    }

    /**
     * Fire an event to all registered listeners.
     * BUG: for-each creates an iterator that fails fast on concurrent add().
     */
    public void fireEvent() {
        for (Runnable listener : listeners) {  // BUG: ConcurrentModificationException risk
            listener.run();
        }
    }

    public int listenerCount() {
        return listeners.size();
    }

    public List<Runnable> getListeners() {
        return listeners;
    }
}
