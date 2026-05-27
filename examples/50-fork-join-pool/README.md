# Example 50 — Blocking Tasks in ForkJoinPool.commonPool()

Demonstrates **ForkJoinPoolDetector** catching tasks submitted to the common
`ForkJoinPool` that block on I/O, starving other parallel tasks competing for
the same worker threads.

## The Problem

`ParallelSorter.sortAsync()` submits work to `ForkJoinPool.commonPool()`. The
task internally simulates blocking I/O with `Thread.sleep()`. Because the common
pool has a fixed number of workers equal to `Runtime.availableProcessors() - 1`,
all workers can become stuck waiting, starving every other parallel stream or
`CompletableFuture` that uses the same pool.

A plain `@Test` runs the task on the calling thread and finishes before any
starvation manifests. `ForkJoinPoolDetector` records task timing and reports
when tasks hold workers for unexpectedly long durations.

## How to Reproduce

1. Open `ParallelSorterTest.java`.
2. Remove the `@Disabled` annotation from `testSortAsync_concurrent_detectsCommonPoolBlocking`.
3. Run the test — `ForkJoinPoolDetector` will flag the long-running tasks.

## The Fix

Use a dedicated `ForkJoinPool` with `ManagedBlocker` for blocking tasks, or
offload I/O to a separate `ExecutorService` and keep the common pool for
CPU-bound work only.
