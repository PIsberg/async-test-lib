package se.deversity.asynctest.example.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Global registry of active {@link EventEmitter} instances.
 *
 * <p>Thread-safe storage — the registry itself is safe, but an emitter can
 * register itself before it is fully constructed, making the stored reference
 * observable to other threads in a partially-initialized state.
 */
public class EventRegistry {

    private static final Set<EventEmitter> emitters = ConcurrentHashMap.newKeySet();

    private static volatile Consumer<EventEmitter> onRegistered = emitter -> { };

    /**
     * Registers an emitter and tells anybody listening.
     *
     * <p>Called from the EventEmitter constructor, which is too early: the listener receives a
     * reference to an object whose fields have not been assigned yet. A registry that notifies
     * synchronously is ordinary; a constructor that registers before it is finished is the bug.
     *
     * @param emitter the emitter registering itself
     */
    public static void register(EventEmitter emitter) {
        emitters.add(emitter);
        onRegistered.accept(emitter);
    }

    /**
     * Installs a registration listener.
     *
     * @param listener called with each emitter as it registers, which is mid-construction
     */
    public static void observeRegistrations(Consumer<EventEmitter> listener) {
        onRegistered = listener;
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
