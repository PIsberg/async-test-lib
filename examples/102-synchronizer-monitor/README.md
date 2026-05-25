# Example 102 — Synchronizer Monitor

Demonstrates **SynchronizerMonitor** detecting over-synchronization and barrier issues.

## The Problem

`CoordinationService.execute()` acquires three independent synchronization primitives
in sequence — a `Semaphore`, a `ReentrantLock`, and a `CountDownLatch` — for every
single task execution. This creates unnecessary contention, makes the locking protocol
hard to reason about, and introduces risk of partial completion (e.g. latch counted down
but lock not released on an exception path).

Under concurrency, threads arrive at the Semaphore in different orders and some fail to
advance through all three primitives, leaving barriers in an incomplete state.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsOverSynchronization` in
   `CoordinationServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **SynchronizerMonitor** will report incomplete barrier advances (fewer parties
   arrived than expected) and duplicate arrivals.

## The Fix

Use a single `ReentrantLock` for mutual exclusion. Replace the `CountDownLatch` with
a simpler condition variable if signalling is needed. Semaphores are only needed when
limiting concurrency to N > 1.
