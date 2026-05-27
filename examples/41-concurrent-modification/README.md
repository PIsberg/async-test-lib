# Example 41 — Concurrent Modification

Demonstrates **ConcurrentModificationDetector**: an `ArrayList` of event
listeners is iterated by `fireEvent()` while another thread calls `register()`,
causing `ConcurrentModificationException`.

## The Problem

`EventBusService` stores listeners in a plain `ArrayList`. `fireEvent()`
iterates the list with a for-each loop. `register()` adds to the same list.
When both operations run simultaneously, the ArrayList's `modCount` check fails
and `ConcurrentModificationException` is thrown mid-dispatch, leaving some
listeners uncalled.

## How to Reproduce

1. Remove `@Disabled` from `testEventBus_concurrent_detectsModification`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ConcurrentModificationDetector** report showing
   that the listener list was modified while an iteration was in progress.

**Fix**: protect both `register()` and `fireEvent()` with `synchronized(listeners)`
blocks, or replace `ArrayList` with `CopyOnWriteArrayList` (appropriate here
because reads dominate writes).
