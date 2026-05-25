# Example 99 — Atomic Non-Atomic Update

Demonstrates **AtomicNonAtomicUpdateDetector** catching a non-atomic compound operation.

## The Problem

`HitCounter.increment()` updates an `AtomicInteger` using the non-atomic pattern:
`counter.set(counter.get() + 1)`. While each individual `get()` and `set()` is atomic,
the compound read-modify-write sequence is not. Two threads can both read the same value,
each compute `value + 1`, and both write the same result — silently losing one increment.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsNonAtomicUpdate` in `HitCounterTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **AtomicNonAtomicUpdateDetector** will report the non-atomic get+set sequence detected
   on the `hit-counter` field.

## The Fix

Replace `counter.set(counter.get() + 1)` with `counter.incrementAndGet()`, which performs
the increment atomically using a hardware CAS instruction.
