# Example 88 — Timer Misuse

**Detector**: `TimerDetector`  
**Flag**: `detectTimerIssues = true`

## The Problem

`ReminderService` uses a single `java.util.Timer` instance for all reminders.
`Timer` has one background thread. If any scheduled `TimerTask` takes longer
than expected, every subsequent task is delayed by the same amount. Worse, the
`Timer` is never cancelled — the background thread keeps the JVM alive and
accumulates tasks across test invocations.

Under concurrency:
- Multiple invocations register new tasks on the same `Timer`.
- The single-threaded execution serialises all tasks regardless of their delay.
- The un-cancelled timer thread leaks between test runs.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsTimerIssues` and
run the test. `TimerDetector` tracks registered timers and tasks scheduled on
them, and reports timers that are never cancelled.

## The Fix

Replace `java.util.Timer` with `ScheduledExecutorService` (e.g.,
`Executors.newScheduledThreadPool(n)`), which uses multiple threads and
recovers from task exceptions without killing the scheduler. Always call
`shutdown()` in a `finally` block or `@AfterEach`.
