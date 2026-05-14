# Example 32 — ReadWriteLock Writer Starvation

## The Problem

`ReadHeavyCache` protects its product-catalog map with a
`ReentrantReadWriteLock(false)` — the default non-fair configuration.
A non-fair lock grants the read lock to any arriving reader thread as long
as no writer currently **holds** the lock. It does not consider whether a
writer is **waiting**.

In production, where hundreds of threads read product listings per second
and one background thread invalidates stale prices every few seconds:

```
Writer thread: waiting for write lock...
 ├─ Reader-1 acquires read lock  ← admitted immediately (non-fair)
 ├─ Reader-2 acquires read lock  ← admitted immediately
 ├─ Reader-3 acquires read lock  ← admitted immediately
 ├─ ...
 └─ Writer still waiting — new readers keep jumping the queue
```

Stale prices remain visible to all readers for far longer than the intended
TTL. Under extreme load, the writer may wait indefinitely.

## Why This Happens

The non-fair policy exists to maximize throughput in read-dominated workloads:
allowing incoming readers to proceed without queuing behind a waiting writer
gives higher aggregate throughput in the common case. But it provides **no
bound** on write wait time. A continuous read stream can starve a waiting
writer indefinitely.

The `fair = false` default is easy to overlook — most developers do not
notice the boolean parameter or do not know its implications.

## How to Reproduce

1. Open `ReadHeavyCacheTest`.
2. Remove `@Disabled` from `testCache_concurrent_detectsWriterStarvation`.
3. Run the test.

`ReadWriteLockMonitor` will report:

```
READ-WRITE LOCK FAIRNESS ISSUES:

Reader-dominated locks (may starve writers):
  - catalog-cache-lock: 87.5x more reads than writes (may cause writer starvation)
  Fix: Use writer preference or fair RWLock

Starved writers:
  - catalog-cache-lock: Writers starved 7 times (max wait: 120ms)

Long write wait times:
  - catalog-cache-lock: Max write wait time 120ms
```

## The Solution

Switch to a **fair** lock so that readers and writers are granted the lock
in arrival order:

```java
// Before (non-fair — default)
private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

// After (fair — bounded writer wait)
private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
```

With `fair = true`, once a writer is queued, newly arriving readers must
wait behind it. This bounds the maximum write latency at the cost of
slightly lower read throughput under extremely high concurrency.

If throughput is critical, consider `StampedLock` with optimistic reading:

```java
StampedLock stampedLock = new StampedLock();

// Read (optimistic — no lock acquired if no write is in progress)
long stamp = stampedLock.tryOptimisticRead();
String value = store.get(key);
if (!stampedLock.validate(stamp)) {
    stamp = stampedLock.readLock();
    try { value = store.get(key); } finally { stampedLock.unlockRead(stamp); }
}
```

## Key Takeaways

- `new ReentrantReadWriteLock()` defaults to **non-fair**. In read-heavy
  workloads this can silently starve writers.
- Writer starvation does not crash your application — it merely delays cache
  refreshes, configuration reloads, or index updates, producing subtle
  correctness issues that are hard to attribute.
- `ReadWriteLockMonitor` detects the pattern by tracking the read-to-write
  ratio and measuring write wait times. A ratio above 10:1 combined with
  elevated wait times is flagged as a starvation risk.
- Use `ReentrantReadWriteLock(true)` when write latency must be bounded,
  or `StampedLock` when optimistic reading is safe for your access pattern.
