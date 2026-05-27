# Example 89 — Unbounded Queue

**Detector**: `UnboundedQueueDetector`  
**Flag**: `detectUnboundedQueue = true`

## The Problem

`WorkerService` creates its executor with `Executors.newFixedThreadPool(2)`.
Internally this factory uses an unbounded `LinkedBlockingQueue`. When producers
submit work faster than the 2 threads can process it, the queue grows without
any upper bound — eventually exhausting heap memory.

Under concurrency:
- 8 test threads each submit tasks on every invocation.
- The 2-thread pool falls behind immediately.
- The internal queue accumulates hundreds of pending tasks with no backpressure.
- In production this leads to `OutOfMemoryError` before tasks are processed.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsUnboundedQueue`
and run the test. `UnboundedQueueDetector` reports queues created with
`Integer.MAX_VALUE` capacity (the `LinkedBlockingQueue` default).

## The Fix

Use a bounded `ArrayBlockingQueue` or `LinkedBlockingQueue(capacity)` and
a `RejectedExecutionHandler` to apply backpressure when the queue is full.
`ThreadPoolExecutor` constructed directly gives full control over these parameters.
