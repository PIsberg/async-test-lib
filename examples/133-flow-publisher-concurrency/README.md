# Example 133 — Flow Publisher Concurrency

**Detector**: `FlowPublisherConcurrencyDetector` (`DetectorType.FLOW_PUBLISHER_CONCURRENCY`, also usable standalone)

## The Problem

The Reactive Streams specification, which `java.util.concurrent.Flow` adopts verbatim, has a
rule every subscriber is written against:

> **Rule 1.3**: `onSubscribe`, `onNext`, `onError` and `onComplete` signalled to a Subscriber
> MUST be signalled serially.

**Serially does not mean "from a single thread."** A publisher may hop threads between signals
as much as it likes, provided each signal finishes before the next begins. No two signals
overlap in time.

That guarantee is load-bearing. It is what lets a subscriber keep unsynchronised state — a
running total, a buffer, a parser position — without a lock:

```java
public void onNext(Long item) {
    total += item;      // safe *only* because signals are serial
    received++;
}
```

Now the publisher fans out to a pool:

```java
for (int i = 0; i < ticks; i++) {
    long price = 100L + i;
    pool.submit(() -> subscriber.onNext(price));   // several onNext at once
}
```

The subscriber's unsynchronised state is suddenly shared mutable state, and it corrupts. The
cruelty is the stack trace: it points into the *subscriber*, which is correct code, rather
than at the publisher that broke the contract.

## Two related violations

| Violation | Rule | Effect on the subscriber |
|---|---|---|
| Overlapping `onNext` | 1.3 | unsynchronised state corrupts |
| Signal after a terminal event (`onNext` after `onComplete`) | 1.7 | it has already released resources |
| More items than requested | 1.1 | a bounded subscriber overflows |

## The Fix

Serialise in the **publisher**, not by making subscribers thread-safe. The contract belongs to
the publisher, and every downstream operator you compose with is written assuming it holds —
you cannot fix that from the subscriber side.

```java
pool.submit(() -> {
    deliveryLock.lock();
    try {
        subscriber.onNext(price);
    } finally {
        deliveryLock.unlock();
    }
});
```

A drain loop is the usual production shape: one thread at a time takes ownership of emission,
others enqueue and leave. Either way the invariant is the same — one signal in flight.

If you find yourself adding `synchronized` to a subscriber, the bug is upstream.

## How to Detect

```java
var d = new FlowPublisherConcurrencyDetector();
d.recordSubscribe(subscriber, "tickStream", t);
d.recordRequest(subscriber, Long.MAX_VALUE);
d.recordNextStart(subscriber, t);
d.recordNextStart(subscriber, poolThread);   // no end between → flagged
assertTrue(d.analyze().hasIssues());
```

`recordNextStart` / `recordNextEnd` bracket each delivery, which is how overlap is detected:
a second start with no intervening end means two threads are inside `onNext` together. The
report names the overlap explicitly (`overlapping onNext`).

`recordComplete` / `recordError` mark the terminal event, after which any further signal is
reported. `recordRequest` tracks demand so over-delivery can be caught.

Inside `@AsyncTest`, select it with `includes = { DetectorType.FLOW_PUBLISHER_CONCURRENCY }`.
Siblings: `ASYNC_PIPELINE` ([example 98](../98-async-pipeline-monitor/)) and
`COMPLETABLEFUTURE_CHAIN` for the non-reactive async composition equivalents.

### Why the test drives events rather than racing threads

A race that reproduces on demand is not a race. Submitting to a pool and hoping two callbacks
overlap gives a test that passes for the wrong reason most runs. The sequences in the test are
exactly the ones a violating publisher generates, asserted deterministically.

See [`TickPublisherTest`](src/test/java/se/deversity/asynctest/example/TickPublisherTest.java)
for the serial / overlapping / after-terminal walkthrough.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
