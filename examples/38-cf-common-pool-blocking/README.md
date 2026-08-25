# Example 38 — CompletableFuture Common Pool Blocking

Demonstrates **CompletableFutureCommonPoolBlockingDetector**: a task running
on `ForkJoinPool.commonPool()` calls `.get()` on another common-pool future,
blocking a pool thread and potentially starving the pool.

## The Problem

`ReportService.generateReport()` submits work with `CompletableFuture.supplyAsync()`
(no explicit executor — defaults to the common pool) and then calls `.get()`
inside the lambda of another `supplyAsync`. This means a common-pool thread
blocks waiting for another common-pool thread. Under heavy concurrent load the
pool exhausts its parallelism budget and all tasks wait for each other — a
pool-level deadlock.

## How to Reproduce

1. Remove `@Disabled` from `testGenerateReport_concurrent_detectsPoolBlocking`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails:

```
COMPLETABLEFUTURE COMMON POOL BLOCKING DETECTED:
  - Thread 'ForkJoinPool.commonPool-worker-1' made blocking call (Future.get) inside
    CompletableFuture 'fetchData' running on the common ForkJoinPool - starves the pool for
    parallel streams and all other common-pool users in this JVM
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

**Fix**: provide a dedicated `ExecutorService` to all `supplyAsync()` calls so
blocking waits do not occupy common-pool threads.

## How the Detector Is Fed

`CompletableFutureCommonPoolBlockingDetector` is **recording-fed** and deliberately narrow.
`recordBlockingCall` ignores any future it was not first told about through
`recordCommonPoolSubmission`, because waiting on a future from an executor you own starves
nobody and is ordinary code. So both calls have to happen, in that order, from inside the code
doing the work. `ReportService.observeCommonPool` is the seam that arranges it; the hooks
default to no-ops, so the production path never touches the test library.

Before issue #346 this example called `recordBlockingCall(null, ...)` from the test body. The
detector returns immediately on a null future, and would have ignored a real one that was never
registered, so the call was a no-op twice over and the run reported nothing.

One detail to be aware of when reading the output: the detector emits one line per blocking
call, with no deduplication, so this demonstration uses `invocations = 5` rather than 50 purely
to keep the report readable. That is issue #351, not a property of the example.
