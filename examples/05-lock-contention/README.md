# Example 05 — Lock Contention

**Detector**: `LockContentionDetector` (`DetectorType.LOCK_CONTENTION`)
**Severity**: 🟡 High — a throughput problem, not a correctness one

## The Problem

A request counter guards a `HashMap` of per-endpoint counts with one `synchronized (lock)`
block. It is **correct**. Every read and every write is properly ordered, no entry is lost,
and no test will ever catch it doing something wrong.

It is also a queue. Two threads incrementing counters for two completely unrelated endpoints
still serialise on the same monitor, because the lock guards the whole map rather than the
entry either of them cares about. Under load the wait time dominates and throughput flattens
long before the CPUs are busy — the machine looks idle and the service looks slow, which is
a combination that sends people looking in entirely the wrong place.

## The buggy pattern

```java
private final Map<String, Long> counts = new HashMap<>();
private final Object lock = new Object();      // ✗ one lock for every key

void recordRequest(String endpoint) {
    synchronized (lock) {                      // ✗ /orders blocks /health
        counts.merge(endpoint, 1L, Long::sum);
    }
}
```

## The Fix

```java
private final ConcurrentMap<String, LongAdder> counts = new ConcurrentHashMap<>();

void recordRequest(String endpoint) {
    counts.computeIfAbsent(endpoint, k -> new LongAdder()).increment();   // ✓ per key
}
```

Two things happen here. `ConcurrentHashMap` shrinks the critical section from the whole map
to one bin, so unrelated endpoints stop blocking each other. `LongAdder` then spreads the
contention *within* a hot endpoint across per-thread cells, which matters when one endpoint
takes most of the traffic — see [example 125](../125-high-contention-atomic/) for the trade
that makes (`LongAdder` gives up the value-with-the-update).

`ConcurrentHashMap.merge()` alone is already a large improvement and keeps the value a plain
`Long`; reach for `LongAdder` when one key is genuinely hot.

## Why `@Test` Misses It

There is nothing to catch. The code is correct, the assertions pass, and a sequential test
never has two threads to serialise. Contention only exists under contention.

`@AsyncTest` puts N threads on the same lock behind a barrier, and
`LockContentionDetector` reports the monitor by wait time and acquisition count — a number
you can act on, rather than a suspicion.

See [`RequestCounterServiceTest`](src/test/java/se/deversity/asynctest/example/RequestCounterServiceTest.java).
Related: `SLEEP_IN_LOCK` ([example 74](../74-sleep-in-lock/)) and `THREAD_STARVATION`
([example 87](../87-thread-starvation/)).

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
