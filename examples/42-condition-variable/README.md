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
   waiters on `not-empty`: threads the lock shows still parked in `await()` when
   the run was analysed, while the buffer they wait on is not empty.

The test registers each condition with the lock that made it and, for
`not-empty`, the predicate a consumer waits for (`buffer.size() > 0`). Only a
thread parked while that predicate holds is a finding: a consumer parked on an
empty buffer is idle, and a condition registered without its lock or predicate
gets notes, not findings. The service also reports every await, await exit and
signal through its `Probe`; a signal made while nobody waits, and a poll that
times out, are how correct code runs, and neither is reported.

**Fix**: `put()` signals `notEmpty`, the condition its consumers wait on.
