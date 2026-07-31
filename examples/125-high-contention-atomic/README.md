# Example 125 — High-Contention Atomic

**Detector**: `HighContentionAtomicDetector` (`DetectorType.HIGH_CONTENTION_ATOMIC`, also usable standalone)
**Severity**: 🟢 Low — this is an advisory, not a bug

## The Problem

Every other example in this directory is about something being wrong. This one is about
something being **slow**.

`AtomicLong` is thread-safe and `incrementAndGet()` always returns the right number. But it
is a CAS loop: read the value, compute the successor, publish it if nobody else got there
first, otherwise start over. Uncontended, it succeeds on the first try. Contended, every
thread is spinning against a single cache line that ping-pongs between cores — and
throughput collapses well before the CPUs look busy, because the work being done is cache
coherence traffic, not counting.

`LongAdder` exists for exactly this. It spreads the same total across per-thread cells and
adds them up in `sum()`.

## The pattern

```java
private final AtomicLong requestCount = new AtomicLong();   // one hot cache line

long recordRequest() {
    return requestCount.incrementAndGet();                  // every request thread, every request
}
```

## The Recommendation

```java
private final LongAdder requestCount = new LongAdder();

void recordRequest() {
    requestCount.add(1);        // ✓ contention spread across cells
}

long total() {
    return requestCount.sum();  // exact once the writers are done
}
```

**Read the trade before applying it.** `LongAdder.add()` returns nothing, and `sum()` is not
atomic with respect to concurrent adds. You are giving up the one thing `AtomicLong` offers
beyond correctness: *the resulting value, atomically, as part of the update*. If all you
ever do is report the total — metrics, statistics, counters — you were never using that
guarantee and `LongAdder` is free. If you allocate sequence numbers or IDs, or check "did
this increment cross the limit?", you are using it, and the advisory does not apply. That is
why this detector reports LOW and never fails a build on its own.

## How to Detect

```java
var d = new HighContentionAtomicDetector();       // or new HighContentionAtomicDetector(threshold)
d.recordCasAttempt(counter, succeeded);
// ... enough attempts, enough threads, enough failures → advisory (LOW)
assertTrue(d.analyze().hasIssues());
```

Three conditions must **all** hold before anything is reported:

| Condition | Default |
|---|---|
| distinct threads touching the instance | ≥ 2 |
| recorded attempts | ≥ 1000 (`DEFAULT_ATTEMPT_THRESHOLD`, constructor-overridable) |
| failed-CAS ratio | ≥ 10% |

A quiet counter is never flagged, and neither is a shared one that simply is not contended —
sharing is not the problem, losing races is. The report quotes the actual attempt count,
thread count and failure percentage so you can judge the trade with numbers rather than a
rule of thumb.

Inside `@AsyncTest`, grab it with `AsyncTestContext.highContentionAtomicDetector()`, select
it alone with `includes = { DetectorType.HIGH_CONTENTION_ATOMIC }`, or drop it with
`excludes`. Related: `FALSE_SHARING` (adjacent counters on one cache line) and
`ATOMIC_NON_ATOMIC_UPDATE` (get-then-set, which *is* a correctness bug).

See [`RequestMetricsServiceTest`](src/test/java/se/deversity/asynctest/example/RequestMetricsServiceTest.java)
for the uncontended / shared-but-quiet / genuinely-hot / LongAdder-works /
sequence-numbers-still-need-AtomicLong walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
