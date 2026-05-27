# Example 86 — Thread Pool Deadlock

**Detector**: `ThreadPoolDeadlockDetector`  
**Flag**: `detectThreadPoolDeadlocks = true`

## The Problem

`TaskOrchestrator.orchestrate()` submits task A to a 2-thread fixed pool. Task A
itself submits task B to the **same** pool and calls `.get()` on the resulting
future — blocking its pool thread while waiting for task B to start. Under
concurrent load all pool threads are occupied by blocking task-A instances,
leaving no thread free to start any task B. Every blocked task waits forever:
a classic thread-pool deadlock.

Under concurrency:
- Both pool threads are consumed by tasks waiting for nested tasks.
- No threads remain to run the nested tasks.
- The pool never makes progress without a timeout or a pool resize.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsDeadlockRisk` and
run the test. `ThreadPoolDeadlockDetector` flags nested submissions to the same
pool whose active-task count equals the pool size.

## The Fix

Use a separate executor for nested tasks, or restructure the work as a
`CompletableFuture` chain so no thread blocks waiting for another task in the
same pool.
