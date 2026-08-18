# 143 — Virtual thread resource saturation

**Detector**: `VirtualThreadResourceSaturationDetector` (`DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION`) · **Severity**: 🔴 High

## The bug

```java
// Before: eight workers could never ask for a ninth connection.
ExecutorService pool = Executors.newFixedThreadPool(8);

// After: nothing bounds the arrivals any more.
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();   // BUG: the limit left with the pool

for (Request r : requests) {          // ten thousand of them
    pool.submit(() -> {
        try (Connection c = connections.getConnection()) {   // ten of these exist
            handle(r, c);
        }
    });
}
```

The connection pool never gets bigger. Ten thousand virtual threads against ten connections is
ten queries and a queue of nine thousand nine hundred and ninety, and it surfaces as
acquisition timeouts rather than as anything that looks like a threading bug.

The pool size was doing admission control and nobody wrote that down, so nothing complained
when it was removed.

## The fix

```java
Semaphore admission = new Semaphore(connections.getMaximumPoolSize());   // FIX: sized from the resource

pool.submit(() -> {
    admission.acquire();
    try (Connection c = connections.getConnection()) {
        handle(r, c);
    } finally {
        admission.release();
    }
});
```

JEP 444 puts it plainly: do not pool virtual threads, use a `Semaphore` to limit concurrent
access to a limited resource. Size it *from* the resource so the two cannot drift apart.

Do not reintroduce a small pool of virtual threads to get the limit back — that is the
anti-pattern `VIRTUAL_THREAD_POOLING` reports.

## What the detector observes

Declare the capacity with `registerResource(name, capacity)`, then bracket each acquisition with
`recordAcquireStart` and `recordAcquired`, or `recordAcquireAbandoned` when the wait gives up.
One finding: 🔴 **High** when more virtual threads were waiting at one moment than the resource
can ever serve. Peak virtual waiters versus capacity — both counts. It is the virtual waiters
that are counted, not everyone in the queue: a burst of platform threads that outran the pool,
with a virtual thread passing through at some other moment, is a bounded pool's problem
(`THREAD_POOL_DEADLOCK`) and is not attributed to a fan-out that never happened.

The abandon call is not optional bookkeeping: a timeout is how this hazard surfaces, and a caller
that times out and says nothing stays in the count for the rest of the run, so every later queue
reads one deeper than it was. A fan-out correctly bounded at exactly the capacity would then be
reported as one over it.

Record the start immediately before the resource's own acquisition, *inside* the semaphore. A
caller queued on the semaphore is not queued on the resource, and recording it as such would
report the fix as the bug.

A fan-out bounded by a semaphore of the resource's own size never queues past capacity and is
silent, and so is a platform-only workload — a bounded pool cannot produce this hazard, and
`THREAD_POOL_DEADLOCK` already owns that ground.

There is deliberately **no** finding for "more callers held the resource than its capacity",
though it looks like the obvious second one. A caller returns the resource and *then* records
having done so, and in that window the next caller can legitimately be granted it — so a count
above the capacity is instrumentation skew as often as a real breach. That version of the rule
existed briefly and CI caught it reporting a correctly bounded pool.

## Run it

```bash
mvn test                 # or: gradle test
```
