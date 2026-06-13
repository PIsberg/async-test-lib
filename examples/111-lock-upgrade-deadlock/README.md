# Example 111 — Lock Upgrade Deadlock

This example demonstrates how `LockUpgradeDeadlockDetector` flags write lock acquisition attempts made while holding a read lock.

## The Problem

In Java's `ReentrantReadWriteLock`, a thread holding a read lock cannot upgrade to a write lock directly. If it attempts
to call `writeLock().lock()`, it will block forever waiting for all read locks (including its own) to be released. This
causes a silent, single-threaded or multi-threaded deadlock.

## The Test

1. Under `src/test/java/se/deversity/asynctest/example/LockUpgradeDeadlockTest.java`, the test:
   - Acquires a read lock.
   - Attempts to acquire a write lock.
   - Records the lock actions using `AsyncTestContext.lockUpgradeDeadlockDetector().recordReadLockAcquired(...)` and `recordWriteLockAcquisitionAttempt(...)`.
2. When `@AsyncTest` runs, `LockUpgradeDeadlockDetector` flags the upgrade attempt as a deadlock hazard.
