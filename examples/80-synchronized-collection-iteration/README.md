# Example 80 — Synchronized Collection Iteration

**Detector**: `SynchronizedCollectionIterationDetector`  
**Flag**: `detectSynchronizedCollectionIteration = true`

## The Problem

`SyncListService` wraps an `ArrayList` with `Collections.synchronizedList()`.
Individual operations like `add()` and `get()` are individually synchronized,
but iteration via `for (String s : items)` is not — each `next()` call on the
iterator acquires and releases the lock separately. A concurrent `add()` between
two `next()` calls increments the `modCount`, causing
`ConcurrentModificationException`.

The `Collections.synchronizedList` Javadoc explicitly states:

> It is imperative that the user manually synchronize on the returned list when
> traversing it via Iterator, Spliterator or Stream.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsUnsafeIteration`
and run the test. `SynchronizedCollectionIterationDetector` records every
iteration started without the iterator's lock held, and the report flags the
unsafe iteration pattern.

## The Fix

Wrap the iteration loop in `synchronized(items) { ... }`:

```java
synchronized (items) {
    for (String item : items) { System.out.println(item); }
}
```
