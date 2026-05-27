package se.deversity.asynctest.example.service;

import java.lang.ref.WeakReference;

/**
 * Wraps a cached value in a {@link WeakReference} to allow GC reclamation.
 *
 * <p><strong>Bug:</strong> {@link #process()} checks {@code ref.get() != null} and then
 * immediately calls {@code ref.get().doWork()} again. Between the two calls the GC can
 * collect the weakly-reachable object, making the second {@code get()} return {@code null}
 * and throwing a {@link NullPointerException} at runtime.
 *
 * <p><strong>Fix:</strong> Assign {@code ref.get()} to a local variable once and
 * null-check that local variable — a strong reference on the stack prevents GC reclamation
 * for the duration of the method.
 *
 * @param <T> the type of the cached payload; must implement {@link Workable}
 */
public class WeakCacheEntry<T extends WeakCacheEntry.Workable> {

    /** Objects stored in this cache must implement this interface. */
    public interface Workable {
        void doWork();
    }

    private final WeakReference<T> ref;
    private final String name;

    public WeakCacheEntry(T value, String name) {
        this.ref = new WeakReference<>(value);
        this.name = name;
    }

    /**
     * Processes the cached value if it is still reachable.
     * Bug: calls ref.get() twice — GC may collect between the checks.
     */
    public void process() {
        // BUG: ref.get() called twice; GC can collect between the two calls
        if (ref.get() != null) {
            ref.get().doWork();  // NullPointerException if collected between the ifs
        }
    }

    /** Returns the underlying WeakReference for instrumentation in tests. */
    public WeakReference<T> getRef() {
        return ref;
    }

    /** Returns the cache entry name. */
    public String getName() {
        return name;
    }
}
