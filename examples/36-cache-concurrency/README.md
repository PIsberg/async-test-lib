# Example 36 — Cache Concurrency

Demonstrates **CacheConcurrencyDetector**: a plain `HashMap` used as a shared
cache without any synchronization, causing data corruption under concurrent load.

## The Problem

`UserCacheService` uses `new HashMap<>()` as its internal cache. `HashMap` is
explicitly not thread-safe. Concurrent `put()` calls can corrupt its internal
array (infinite loops in JDK 7, lost updates or `ConcurrentModificationException`
in JDK 8+). Concurrent `get()` during a structural modification returns stale
or null values even for keys that were written moments earlier.

## How to Reproduce

1. Remove `@Disabled` from `testCache_concurrent_detectsRace`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **CacheConcurrencyDetector** report describing
   unsynchronized concurrent reads and writes to the HashMap cache.

**Fix**: replace `HashMap` with `ConcurrentHashMap`, or wrap all cache
accesses in a `synchronized` block.
