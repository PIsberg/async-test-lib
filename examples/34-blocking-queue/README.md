# Example 34 — Blocking Queue Misuse

Demonstrates **BlockingQueueDetector**: a bounded queue held at its capacity by a
fire-and-forget producer, plus the null-dereference crash that comes from using
non-blocking `poll()` without a guard.

## The Problem

`WorkQueueService` wraps an `ArrayBlockingQueue` with capacity 5.

- **Producer** calls `offer(task)`, which returns `false` when the queue is full. Callers
  routinely ignore that return, so tasks vanish with no error.
- **Consumer** calls `poll()`, which returns `null` when the queue is empty. `processNext()`
  calls `toUpperCase()` on the result without a null check, so an empty queue is a
  `NullPointerException`.

Under sequential load the queue never fills, so `@Test` passes. Under concurrent load
(8 threads x 50 invocations) with nothing draining it, the queue reaches 5/5 on the first
round and stays there.

## What the detector will and will not say about this

This is worth reading before the reproduction steps, because this example used to promise
something the library deliberately stopped saying.

**A rejected `offer()` is not a finding.** This is what correct backpressure looks like:

```java
if (!queue.offer(task)) {
    retryLater(task);        // the return value was read, and acted on
}
```

`BlockingQueueDetector` counts rejections and shows the count, but does not gate on it,
because gating on it printed "BLOCKING QUEUE ISSUES DETECTED" for every correct bounded
queue. The same goes for a null `poll()`: the textbook drain loop
`while ((x = q.poll()) != null)` ends with exactly one null every time.

**Saturation is a finding.** A queue sitting at its bound says the sizing or the drain rate
is wrong, and that is what `hasIssues()` reports on. This demonstration produces it, and the
rejection count appears alongside it in the same report.

## How to Reproduce

1. Remove `@Disabled` from `testWorkQueue_concurrent_detectsSaturation`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails:

```
BLOCKING QUEUE ISSUES DETECTED:
  Silent Failures (offer returned false):
    - work-queue: offer() returned false 395 times (queue full; check the caller handles
      the rejected item)
  Queue Saturation:
    - work-queue: queue reached 5/5 capacity (saturation risk)
  Queue Activity:
    - work-queue: offers: 400 (success: 5, failed: 395), polls: 0 ..., max size: 5
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

**Fix**: replace `offer()` with `put()` (blocks until space is available) and replace
`poll()` with `poll(timeout, unit)` or `take()`, adding a null-check guard where a timeout
poll is required. If the queue really must be lossy, read the `offer()` return and record
the drop rather than discarding it silently.

## Files in This Example

- **`WorkQueueService.java`** - the bounded queue, `offer()` on the way in and unguarded
  `poll()` on the way out
- **`WorkQueueServiceTest.java`**
  - `testProcessNext_emptyQueue_throwsNullPointerException()` - the half of the bug that
    needs no detector at all
  - `testBlockingQueueDetector_queueAtItsBound_reports()` - the detector's positive direction
  - `testBlockingQueueDetector_queueWellBelowItsBound_isSilent()` - and its negative one
  - `testWorkQueue_concurrent_detectsSaturation()` - the `@AsyncTest` demonstration
