package se.deversity.asynctest.example.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of active {@link EventEmitter} instances.
 *
 * <p>Thread-safe storage — the registry itself is safe, but an emitter can
 * register itself before it is fully constructed, making the stored reference
 * observable to other threads in a partially-initialized state.
 */
public class EventRegistry {

    private static final Set<EventEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** Registers an emitter. Called from the EventEmitter constructor — may be too early. */
    public static void register(EventEmitter emitter) {
        emitters.add(emitter);
    }

    /** Unregisters an emitter. */
    public static void unregister(EventEmitter emitter) {
        emitters.remove(emitter);
    }

    /** Returns a snapshot of registered emitters. */
    public static Set<EventEmitter> getAll() {
        return Set.copyOf(emitters);
    }

    /** Clears all registered emitters (for test teardown). */
    public static void clear() {
        emitters.clear();
    }
}
