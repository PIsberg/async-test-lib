# Example 103 — Thread Pool Monitor

Demonstrates **ThreadPoolMonitor** detecting pool saturation and task queuing issues.

## The Problem

`WorkloadService` uses a fixed thread pool of only 2 threads. Under concurrent load,
8 test threads each submit multiple tasks, so the pool queue grows rapidly. With only
2 worker threads processing the queue, the active thread count is always at capacity,
tasks pile up in the queue, and response latency increases unboundedly. In the worst
case, if the pool uses a bounded queue, tasks are rejected.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsPoolSaturation` in
   `WorkloadServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **ThreadPoolMonitor** will report thread starvation (all 2 threads busy) and a
   saturated queue with peak depth well above the core pool size.

## The Fix

Size the thread pool according to the expected concurrency level. For CPU-bound tasks
use `Runtime.getRuntime().availableProcessors()`; for I/O-bound tasks use a larger pool
or switch to virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`).
