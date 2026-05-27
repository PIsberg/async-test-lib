# Example 95 — Wakeup Issues (Spurious Wakeup)

Demonstrates **WakeupDetector** catching spurious-wakeup vulnerability.

## The Problem

`SpuriousWakeupService.waitUntilReady()` wraps `monitor.wait()` in an `if` statement
instead of a `while` loop. The JVM specification permits `wait()` to return at any time
(a "spurious wakeup") even without a `notify()`. With an `if` guard, the thread proceeds
as if the condition is met even when it is not, causing logic errors or data corruption.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsSpuriousWakeup` in
   `SpuriousWakeupServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **WakeupDetector** will report spurious wakeup events and lost notifications.

## The Fix

Replace `if (!ready) monitor.wait()` with `while (!ready) { monitor.wait(); }` so the
thread re-checks the condition after every wakeup, whether genuine or spurious.
