# Example 42 — Condition Variable Misuse

Demonstrates **ConditionVariableDetector**: a bounded buffer's `put()` signals
the wrong condition, so a consumer that was already waiting for an item is never
woken, although the item is in the buffer.

## The Problem

`BoundedBufferService` maintains two conditions — `notFull` (producers wait
here when the buffer is full) and `notEmpty` (consumers wait here when the
buffer is empty). `put()` adds an item and then calls `notFull.signal()`
instead of `notEmpty.signal()`.

A consumer that arrives after the item never waits, and a single thread doing
`put()` then `take()` never blocks, so sequential tests pass. Only a consumer
already parked on `notEmpty` when `put()` runs is lost: with `take()` it hangs
forever, with `poll(timeout)` it returns empty-handed while the item sits there.

## How to Reproduce

1. Remove `@Disabled` from `testBuffer_concurrent_detectsStrandedConsumer`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **ConditionVariableDetector** report listing stuck
   waiters on `not-empty`: threads still inside `await()` when the run was
   analysed.

The service reports every await, await exit and signal through its `Probe`, so
the detector pairs each wait with the signals that could have woken it. A signal
made while nobody waits, and a poll that times out, are how correct code runs,
and neither is reported on its own.

**Fix**: `put()` signals `notEmpty`, the condition its consumers wait on.
