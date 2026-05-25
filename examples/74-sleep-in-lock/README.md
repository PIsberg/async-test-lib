# Example 74 — Sleep In Lock

**Detector**: `SleepInLockDetector`  
**Flag**: `detectSleepInLock = true`

## The Problem

`ThrottledService.processRequest()` holds a `synchronized` lock on `this` while
calling `Thread.sleep(50)` to implement rate limiting. The lock is held for the
entire 50 ms sleep, so every other thread trying to call `processRequest()` is
forced to wait in the `synchronized` queue for the full sleep duration.

Under concurrency this causes:
- **Severe contention**: N threads each sleeping 50 ms while holding the lock
  means throughput drops to 1 request per 50 ms regardless of thread count.
- **Priority inversion**: high-priority threads block behind a sleeping
  low-priority thread.
- **Potential deadlock risk** if the sleeping thread also holds a second lock.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsSleepInLock` and
run the test. `SleepInLockDetector` records every `Thread.sleep()` call made
while the calling thread holds a monitor and fails the test with a detailed
report.

## The Fix

Release the lock before sleeping, or use `Condition.await(timeout, unit)` with
an explicit lock to coordinate threads without blocking other callers.
