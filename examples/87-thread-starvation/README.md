# Example 87 — Thread Starvation

**Detector**: `ThreadStarvationDetector`  
**Flag**: `detectThreadStarvation = true`

## The Problem

`PriorityQueueService` shares a single non-fair lock between high-priority and
low-priority tasks. High-priority tasks repeatedly acquire the lock and hold it
while sleeping (simulating slow work). Because the lock is not fair, threads
waiting for it are not queued in FIFO order, so high-priority tasks can
continuously re-acquire the lock before low-priority tasks ever get a turn.

Under concurrency:
- Low-priority threads queue up waiting for the lock.
- The wait time far exceeds the starvation threshold.
- Eventually, low-priority work is delayed or never completes within the test.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsStarvation` and
run the test. `ThreadStarvationDetector` measures queue wait times and reports
tasks that waited beyond the configured threshold.

## The Fix

Use a fair `ReentrantLock(true)` so waiting threads are served in FIFO order,
or decouple high-priority and low-priority work onto separate executors.
