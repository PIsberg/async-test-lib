# Example 43 — Copy-on-Write Collection Misuse

Demonstrates **CopyOnWriteCollectionDetector**: `CopyOnWriteArrayList` is used
in a write-heavy workload, causing O(n) array copies on every write and
excessive GC pressure under concurrent load.

## The Problem

`MetricsService` records a timestamp on every incoming request by calling
`timestamps.add(System.currentTimeMillis())`. The backing collection is
`CopyOnWriteArrayList<Long>`. Every `add()` allocates and copies the entire
backing array — an O(n) operation. With 8 threads each submitting 50 events,
the array is copied thousands of times, wasting CPU and generating heap churn.

`CopyOnWriteArrayList` is designed for **read-heavy** workloads where writes
are rare. Using it in a write-heavy scenario is a performance anti-pattern.

## How to Reproduce

1. Remove `@Disabled` from `testRecordEvent_concurrent_detectsWriteHeavy`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **CopyOnWriteCollectionDetector** report noting a
   high write-to-read ratio on the `CopyOnWriteArrayList` instance.

**Fix**: replace `CopyOnWriteArrayList` with a `ConcurrentLinkedQueue` or
a `LongAdder` counter, which are allocation-free on the hot write path.
