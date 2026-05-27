package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;

/**
 * BUGGY service that demonstrates unsynchronized HashMap cache access.
 *
 * BUG: A plain HashMap is shared across threads. Concurrent put() and get()
 *      calls are not thread-safe — they can produce lost updates,
 *      ConcurrentModificationException, or infinite CPU loops under resize.
 *
 * FIX: Replace HashMap with ConcurrentHashMap, or guard all accesses with
 *      synchronized(cache) { ... }.
 */
public class UserCacheService {

    // BUG: HashMap is not thread-safe
    private final Map<String, String> cache = new HashMap<>();

    /** Store a user in the cache. Not thread-safe. */
    public void put(String key, String value) {
        cache.put(key, value);  // BUG: unsynchronized structural modification
    }

    /** Retrieve a user from the cache. Not thread-safe. */
    public String get(String key) {
        return cache.get(key);  // BUG: may race with concurrent put()
    }

    public int size() {
        return cache.size();
    }

    public Map<String, String> getCache() {
        return cache;
    }
}
