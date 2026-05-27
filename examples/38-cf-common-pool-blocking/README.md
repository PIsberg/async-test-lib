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
3. The test fails with a **CompletableFutureCommonPoolBlockingDetector** report
   identifying the blocking call on a common-pool thread.

**Fix**: provide a dedicated `ExecutorService` to all `supplyAsync()` calls so
blocking waits do not occupy common-pool threads.
