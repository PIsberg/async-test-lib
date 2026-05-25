package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * BUGGY service that demonstrates synchronizing on a non-final field.
 *
 * BUG: lockObject is not final. If reassignLock() is called concurrently,
 *      thread A holds the old lock while thread B acquires the new lock —
 *      both are now "inside" their respective synchronized blocks at the same
 *      time, breaking mutual exclusion over the shared cache map.
 *
 * FIX: declare lockObject as {@code private final Object lockObject = new Object()}.
 *      A final field can never be reassigned so all threads always synchronize
 *      on the same monitor.
 */
public class LockableCache {

    // BUG: non-final — can be reassigned, breaking the synchronization invariant.
    private Object lockObject = new Object();

    private final Map<String, String> cache = new HashMap<>();

    /**
     * Put a key-value pair into the cache.
     * BUG: synchronizes on a potentially reassigned lock object.
     */
    public void put(String key, String value) {
        synchronized (lockObject) {
            cache.put(key, value);
        }
    }

    /**
     * Get a value from the cache.
     * BUG: synchronizes on a potentially reassigned lock object.
     */
    public String get(String key) {
        synchronized (lockObject) {
            return cache.get(key);
        }
    }

    /**
     * Reassign the lock object.
     * BUG: any thread waiting on the old lock continues to block on it while
     *      new threads acquire the new lock — mutual exclusion is broken.
     */
    public void reassignLock() {
        lockObject = new Object();
    }

    /** Expose the current lock object for detector registration. */
    public Object getLockObject() {
        return lockObject;
    }
}
