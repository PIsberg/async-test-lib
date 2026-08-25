package se.deversity.asynctest.example.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * BUGGY service that demonstrates public lock exposure.
 *
 * <p>BUG, in two halves that are only a bug together:
 * <ul>
 *   <li>Every method that touches state is {@code synchronized}, so the lock guarding this
 *       object's state <em>is this object</em>.</li>
 *   <li>{@link #forResource(String)} hands the object to anybody who asks for it.</li>
 * </ul>
 *
 * <p>A caller who wants two of these operations to be atomic will reach for
 * {@code synchronized (manager) { ... }}, because it works, and now an unrelated piece of code
 * decides how long every other thread waits. If that caller also holds another lock, the
 * deadlock is somebody else's to debug.
 *
 * <p>FIX: guard the state with a lock nobody else can name.
 * {@code private final Object lock = new Object();} and {@code synchronized (lock)} inside the
 * methods. External code can still call the methods; it can no longer take the lock.
 *
 * <p>INSTRUMENTATION: PublicLockExposureDetector reports the <em>intersection</em>: an object
 * that was both used as a lock and published. Two hooks, both keyed on the same instance, are
 * what it needs. They default to no-ops, so the production path never touches the test library.
 */
public class SharedResourceManager {

    /** Where published managers live. Public reachability is half the bug. */
    private static final Map<String, SharedResourceManager> DIRECTORY = new ConcurrentHashMap<>();

    private static volatile Consumer<Object> onPublished = manager -> { };

    private volatile Runnable onSynchronizedOnThis = () -> { };

    private int resourceValue;

    /**
     * Looks up, or creates, the manager for a named resource, and hands it out.
     *
     * <p>BUG: the object returned is the same object its own methods synchronize on.
     *
     * @param name the resource name
     * @return the shared manager for that resource
     */
    public static SharedResourceManager forResource(String name) {
        SharedResourceManager manager =
                DIRECTORY.computeIfAbsent(name, key -> new SharedResourceManager());
        onPublished.accept(manager);
        return manager;
    }

    /** Empties the directory, so one test does not inherit another's managers. */
    public static void resetDirectory() {
        DIRECTORY.clear();
    }

    /**
     * Installs the publication hook.
     *
     * @param published called with each manager as it is handed to a caller
     */
    public static void observePublication(Consumer<Object> published) {
        onPublished = published;
    }

    /**
     * Access the protected resource.
     *
     * <p>BUG: {@code synchronized} on the instance method means the monitor is {@code this}.
     *
     * @return the new value
     */
    public synchronized int accessResource() {
        onSynchronizedOnThis.run();
        resourceValue++;
        return resourceValue;
    }

    /**
     * {@return the current value}
     */
    public synchronized int getResourceValue() {
        onSynchronizedOnThis.run();
        return resourceValue;
    }

    /**
     * Installs the locking hook.
     *
     * @param synchronizedOnThis called from inside each synchronized method
     */
    public void observeLocking(Runnable synchronizedOnThis) {
        this.onSynchronizedOnThis = synchronizedOnThis;
    }
}
