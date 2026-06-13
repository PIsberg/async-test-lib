# Example 112 — TryLock Misuse

This example demonstrates how `TryLockMisuseDetector` flags unlock calls on a lock where a preceding `tryLock()` failed.

## The Problem

`Lock.tryLock()` returns a boolean indicating whether the lock was successfully acquired.
If a thread fails to check this return value and unconditionally executes `unlock()` in a `finally` block,
it will attempt to release a lock it does not hold, resulting in an `IllegalMonitorStateException` or releasing
a lock held by another thread.

## The Test

1. Under `src/test/java/se/deversity/asynctest/example/TryLockMisuseTest.java`, the test:
   - Tries to acquire a lock.
   - Records the result using `AsyncTestContext.tryLockMisuseDetector().recordTryLockResult(lock, "my-lock", success, thread)`.
   - Calls `unlock()` and records it using `recordUnlock(lock, "my-lock", thread)`.
2. When `@AsyncTest` runs, `TryLockMisuseDetector` flags the scenario if `unlock()` is called after a failed `tryLock()` (success = false).
