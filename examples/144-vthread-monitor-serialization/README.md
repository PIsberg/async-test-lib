# 144 — Virtual thread monitor serialization

**Detector**: `VirtualThreadMonitorSerializationDetector` (`DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION`) · **Severity**: 🔴 High

## The bug

```java
synchronized (lock) {                 // BUG: one thread at a time, ten thousand arriving
    String cached = sessions.get(key);
    if (cached == null) {
        cached = loadFromDatabase(key);   // the expensive part, inside the lock
        sessions.put(key, cached);
    }
    return cached;
}
```

This is the hazard that outlived pinning. Before JDK 24 a `synchronized` block that blocked
pinned its virtual thread to a carrier, and `VIRTUAL_THREAD_PINNING` reported it. JEP 491
removed the pinning — and that detector now correctly marks monitor events obsolete from JDK 24
on.

The scalability limit did not go with it. `synchronized` still admits one thread at a time, and
with the pool gone nothing bounds how many threads arrive. The carrier is free; the throughput
is not.

That makes it easy to miss, because the fix landed: upgrading to JDK 24 reads as "the pinning
warnings went away" when what actually happened is that the same bottleneck stopped announcing
itself.

## The fix

```java
return sessions.computeIfAbsent(key, this::loadFromDatabase);   // FIX: admits every thread
```

Or keep the lock and shrink what is under it — the state mutation only, with the I/O, the
parsing and the logging outside. Where the serialisation is deliberate, bound the arrivals with
a `Semaphore` so the queue is explicit and has a size you chose.

## What the detector observes

Call `recordMonitorEnter` immediately before the `synchronized` block and
`recordMonitorAcquired` inside it; everything entered and not yet acquired is, by definition,
queued.

Fires when the peak queue depth reaches the threshold (default 4) and at least two of the
waiters were virtual threads. Both numbers are counts. The report also says which side of JDK 24
it is on: below 24 it points at `VIRTUAL_THREAD_PINNING`, which reports the same block; from 24
it says that nothing else does.

`LOCK_CONTENTION` cannot make this call — it has no notion of a virtual thread, so it scores
four platform workers and four thousand virtual ones identically.

A critical section short enough that nobody piles up behind it produces no finding, which is
what stops this from simply reporting every `synchronized` block in the codebase.

## Run it

```bash
mvn test                 # or: gradle test
```
