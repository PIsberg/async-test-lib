# Example 34 — Blocking Queue Misuse

Demonstrates **BlockingQueueDetector**: silent task drops and null-dereference
crashes caused by using non-blocking `offer()`/`poll()` on a capacity-bounded queue.

## The Problem

`WorkQueueService` wraps an `ArrayBlockingQueue` with capacity 5.

- **Producer** calls `offer(task)` — returns `false` silently when the queue is
  full. Tasks are dropped with no error.
- **Consumer** calls `poll()` — returns `null` when the queue is empty. The
  service uses the result without a null check, throwing `NullPointerException`
  under concurrent load.

Under sequential load the queue rarely fills up, so `@Test` passes.
Under concurrent load (8 threads × 50 invocations) the queue saturates
immediately: producers start dropping tasks while consumers race to drain an
already-empty queue.

## How to Reproduce

1. Remove `@Disabled` from `testWorkQueue_concurrent_detectsDrops`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a report from **BlockingQueueDetector** listing offer
   failures and queue-full events detected during the stress run.

**Fix**: replace `offer()` with `put()` (blocks until space is available) and
replace `poll()` with `poll(timeout, unit)` or `take()`, adding a null-check
guard where a timeout poll is required.
