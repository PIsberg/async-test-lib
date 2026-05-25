# Example 75 — Stamped Lock

**Detector**: `StampedLockDetector`  
**Flag**: `detectStampedLockIssues = true`

## The Problem

`PositionTracker.moveTo()` acquires a `StampedLock` write lock via
`lock.writeLock()` but never calls `lock.unlockWrite(stamp)`. Because there is
no `finally` block, any exception (or simply forgetting the unlock call) leaves
the write lock permanently held. All subsequent `writeLock()` calls from any
thread will block indefinitely, causing a live deadlock.

`StampedLock` is not reentrant — even the owning thread cannot re-acquire the
write lock it already holds. This makes an unreleased stamp far more dangerous
than an unreleased `ReentrantLock`.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsUnreleasedStamp`
and run the test. `StampedLockDetector` records every write-lock acquisition and
unlock, then flags any stamp that was acquired but never released.

## The Fix

Always release `StampedLock` stamps in a `finally` block:

```java
long stamp = lock.writeLock();
try {
    x = nx; y = ny;
} finally {
    lock.unlockWrite(stamp);
}
```
