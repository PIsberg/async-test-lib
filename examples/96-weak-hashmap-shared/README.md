# Example 96 — Weak HashMap Shared

Demonstrates **WeakHashMapSharedDetector** catching unsynchronized access to a `WeakHashMap`.

## The Problem

`WeakCacheService` uses a `WeakHashMap<Object, String>` as a shared cache visible to all
threads. `WeakHashMap` is explicitly not thread-safe: concurrent `put()` calls can corrupt
the internal hash table, and GC-triggered entry expiry runs concurrently with all mutations.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsSharedWeakHashMap` in
   `WeakCacheServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **WeakHashMapSharedDetector** will report multiple threads accessing the same
   `WeakHashMap` instance without synchronization.

## The Fix

Replace `WeakHashMap` with a `ConcurrentHashMap` backed by `WeakReference` values,
or wrap access in `Collections.synchronizedMap(new WeakHashMap<>())` with external
locking during iteration.
