# Example 51 — Blocking Call Inside a RecursiveTask

Demonstrates **ForkJoinTaskBlockingDetector** catching a `RecursiveTask` that
calls `Thread.sleep()` inside `compute()`, pinning a ForkJoin worker thread and
preventing work-stealing.

## The Problem

`RecursiveCounter` extends `RecursiveTask<Long>` and calls `Thread.sleep(1)`
inside `compute()` to simulate latency. ForkJoin worker threads are not designed
to block; a sleeping worker cannot steal other tasks from the queue, causing the
pool to become unresponsive under concurrent load.

A plain `@Test` invokes `compute()` on the calling thread where sleep merely
adds a small delay. `ForkJoinTaskBlockingDetector` tracks when a ForkJoin worker
thread enters a blocking call and reports it as a violation.

## How to Reproduce

1. Open `RecursiveCounterTest.java`.
2. Remove the `@Disabled` annotation from `testCompute_concurrent_detectsBlockingInTask`.
3. Run the test — `ForkJoinTaskBlockingDetector` will report the blocking call.

## The Fix

Replace `Thread.sleep()` with `ForkJoinPool.managedBlock()`, or restructure the
task to avoid blocking entirely by delegating I/O to an async callback.
