# Example 66 — Reentrant Lock Imbalance

Demonstrates **ReentrantLockDetector**: a `CounterService` calls `lock.lock()`
twice in the same call chain (once in `increment()` and once in `validate()`)
but only calls `lock.unlock()` once in the `finally` block. After the method
returns the lock hold count is still 1, permanently locking out all other
threads.

## The Problem

`ReentrantLock` is re-entrant: the same thread can call `lock()` multiple times
and the hold count increases accordingly. Each `lock()` call requires a
matching `unlock()`. When `increment()` acquires the lock and then delegates
to `validate()` (which acquires it again), the finally block calls `unlock()`
only once — leaving the hold count at 1. The lock is never fully released and
all subsequent callers from other threads block indefinitely.

## How to Reproduce

1. Remove `@Disabled` from `testIncrement_concurrent_detectsLockImbalance`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ReentrantLockDetector** report showing that lock
   acquire count exceeds release count across invocations.

**Fix**: match every `lock()` with exactly one `unlock()` in a `finally` block.
If `validate()` needs the lock, the outer `finally` must call `unlock()` twice,
or `validate()` should use a separate locking strategy.
