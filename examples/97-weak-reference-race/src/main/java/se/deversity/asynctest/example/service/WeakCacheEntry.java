package se.deversity.asynctest.example.service;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * Wraps a cached value in a {@link WeakReference} to allow GC reclamation.
 *
 * <p><strong>Bug:</strong> {@link #process()} calls {@code ref.get()} twice: once to test for
 * null, and again to use. The second result is never checked. The garbage collector is entitled
 * to collect a weakly-reachable object between those two calls, which makes the second
 * {@code get()} return {@code null} and the dereference a {@link NullPointerException} - one
 * that appears only under memory pressure, on somebody else's machine.
 *
 * <p><strong>Fix:</strong> {@link #processFixed()}. Assign {@code ref.get()} to a local
 * variable once and null-check that. A strong reference on the stack keeps the referent alive
 * for as long as the method needs it.
 *
 * <p><strong>INSTRUMENTATION:</strong> WeakReferenceRaceDetector reports two things: a
 * {@code get()} result used without a null check, and a reference that returned non-null on one
 * thread and null on another. The first is a property of the code and does not depend on the
 * collector running at all, which is what makes it demonstrable. The hooks below report it;
 * they default to no-ops, so the production path never touches the test library.
 *
 * @param <T> the type of the cached payload; must implement {@link Workable}
 */
public class WeakCacheEntry<T extends WeakCacheEntry.Workable> {

    /** Objects stored in this cache must implement this interface. */
    public interface Workable {
        /** Does whatever the cached object exists to do. */
        void doWork();
    }

    private final WeakReference<T> ref;
    private final String name;

    private volatile Consumer<Object> onGet = result -> { };

    private volatile Runnable onUncheckedUse = () -> { };

    /**
     * Creates an entry holding {@code value} weakly.
     *
     * @param value the payload
     * @param name  a label for reports
     */
    public WeakCacheEntry(T value, String name) {
        this.ref = new WeakReference<>(value);
        this.name = name;
    }

    /**
     * Processes the cached value if it is still reachable.
     *
     * <p>BUG: two independent {@code get()} calls, and the second result is used unchecked.
     */
    public void process() {
        T checked = ref.get();
        onGet.accept(checked);
        if (checked != null) {
            // BUG: a second, independent get(). Whatever the first one said, this one may
            // return null, and nothing here looks.
            T used = ref.get();
            onGet.accept(used);
            onUncheckedUse.run();
            used.doWork();
        }
    }

    /**
     * The same operation with one {@code get()} and one null check.
     *
     * <p>The local variable is a strong reference, so the referent cannot be collected while
     * this method is using it.
     */
    public void processFixed() {
        T value = ref.get();
        onGet.accept(value);
        if (value != null) {
            value.doWork();
        }
    }

    /**
     * Installs the hooks WeakReferenceRaceDetector needs. No-ops by default.
     *
     * @param get          called with the result of every {@code ref.get()}, null included
     * @param uncheckedUse called immediately before a {@code get()} result is used unchecked
     */
    public void observeReference(Consumer<Object> get, Runnable uncheckedUse) {
        this.onGet = get;
        this.onUncheckedUse = uncheckedUse;
    }

    /**
     * {@return the underlying WeakReference, which the detector tracks by identity}
     */
    public WeakReference<T> getRef() {
        return ref;
    }

    /**
     * {@return the cache entry name}
     */
    public String getName() {
        return name;
    }
}
