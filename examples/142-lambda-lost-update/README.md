# 142 — Lambda captured-state lost update

**Detector**: `LambdaLostUpdateDetector` (`DetectorType.LAMBDA_LOST_UPDATE`) · **Severity**: 🔴 High

## The bug

```java
int[] hits = {0};                       // the effectively-final workaround

Runnable onRequest = () -> {
    hits[0] = hits[0] + 1;              // BUG: read, add, write - three steps, no lock
};
```

A lambda captures the container, not a copy. The array reference is effectively final so this
compiles, and the contents are as shared and as unguarded as any field. Two threads read the
same value, both add one, both write back — two increments, one survivor. Nothing throws, and
the count is simply wrong.

## The fix

```java
AtomicInteger hits = new AtomicInteger();

Runnable onRequest = () -> hits.incrementAndGet();   // FIX: one operation, no window
```

Or hold one monitor across the **whole** read-modify-write — not around the read and the write
separately, which leaves the same window open while looking safe.

## How this differs from `STATEFUL_LAMBDA`

`StatefulLambdaDetector` reports the *shape*: this lambda ran on several threads and mutated
something it captured. That is a co-occurrence, so it fires the same way on a correctly locked
counter as on a racy one.

This detector compares the values the threads observed, and it needs two things before it
speaks. First, two threads were seen reading the **same pre-value** before writing back. Second,
the recorded updates cannot be laid end to end as one serial chain: a value read twice more
often than it was written back was read after it had already been replaced, whichever way the
events are ordered. Together that is a lost update, not the risk of one. The first alone is not
proof: a flag toggled `false -> true -> false -> true` under a `ReentrantLock` shows two threads
reading `false`, and nothing was lost, because the value came round again under a lock the
detector cannot see. It stays silent on that, and when every recorded update held the same
monitor. An `incrementAndGet()` gives every thread a distinct pre-value, so the atomic version
is silent too.

Where the guarding is inconsistent (some updates hold the monitor, some do not, or they hold
different monitors) the finding stands and says which. That case is worse than no lock at all,
because the code reads as if the sequence were atomic.

## What the detector observes

```java
int before = counter.read();
int after  = before + 1;
counter.write(after);
detector.recordReadModifyWrite(task, "hits", before, after, Thread.currentThread());

// or, naming the monitor that is supposed to make it atomic:
detector.recordReadModifyWrite(task, "hits", before, after, guard, Thread.currentThread());
```

The guard overload samples `Thread.holdsLock(guard)` at the moment of the call, so a lock taken
around only the read or only the write does not suppress anything.

## Run it

```bash
mvn test                 # or: gradle test
```
