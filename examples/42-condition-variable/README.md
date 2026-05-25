# Example 42 — Condition Variable Misuse

Demonstrates **ConditionVariableDetector**: `signal()` is used instead of
`signalAll()` in a bounded buffer, causing threads that are waiting for space
to remain parked even after space becomes available.

## The Problem

`BoundedBufferService` maintains two conditions — `notFull` (producers wait
here when the buffer is full) and `notEmpty` (consumers wait here when the
buffer is empty). The `take()` method calls `notEmpty.signal()` after
removing an item, and `put()` calls `notFull.signal()` after adding one.

With multiple producers and consumers, `signal()` wakes exactly one thread.
If the wrong thread is woken (e.g., another consumer when a producer should
be woken), the producer stays parked even though there is now free space —
leading to indefinite hangs under concurrent load.

## How to Reproduce

1. Remove `@Disabled` from `testBuffer_concurrent_detectsMissedSignal`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ConditionVariableDetector** report noting that
   `signal()` (not `signalAll()`) was used where multiple waiters exist.

**Fix**: replace `signal()` with `signalAll()` on both conditions, or use
a single condition with `signalAll()`.
