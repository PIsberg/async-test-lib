# Example 110 — Spurious Wakeup Hazard

This example demonstrates how `SpuriousWakeupDetector` flags waiting on an object monitor without checking a condition loop.

## The Problem

Object monitors (using `Object.wait()`) or condition variables (`Condition.await()`) can wake up "spuriously" without being notified.
Thus, waits must always be placed inside a condition-checking loop (e.g. `while (!condition) { wait(); }`). Using an `if` block or
omitting the check leaves the code vulnerable to state corruption when a spurious wakeup occurs.

## The Test

1. Under `src/test/java/se/deversity/asynctest/example/SpuriousWakeupTest.java`, the test:
   - Synchronizes on a shared lock object.
   - Performs a wait.
   - Records the wait using `AsyncTestContext.spuriousWakeupHazardDetector().recordWait(lock, "my-lock", false, thread)`.
2. When `@AsyncTest` runs, `SpuriousWakeupDetector` flags the wait because `insideLoop` was marked as `false`.
