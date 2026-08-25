# Example 88 — Timer Misuse

**Detector**: `TimerDetector`  
**Flag**: `detectTimerIssues = true`

## The Problem

`ReminderService` uses a single `java.util.Timer` instance for all reminders, and `Timer` has
exactly one background thread. Two things follow.

**Everything queues.** A task that takes 150ms delays every task behind it by 150ms, whatever
delay each was scheduled with. Eight reminders that all want to fire now fire one after another
over the next second and a bit. `test_oneThreadMeansTheyQueue` pins that with no detector
involved: three 150ms tasks take at least 450ms.

**A task that throws takes the scheduler with it.** An uncaught exception in a `TimerTask` kills
the timer thread, and `Timer` responds by cancelling every task still scheduled, silently. The
first anybody hears about it is the next `schedule()` call throwing `IllegalStateException`.

## What the detector reports

Those two, and only those two: `hasIssues()` gates on `timerThreadFailures` and
`longRunningTaskWarnings`. It does **not** report a timer that was never cancelled, which is what
this example used to claim. Its demonstration registered the timer, recorded a schedule, and
stopped, so the detector never saw a task run, never timed one, and never heard about an
exception. The report was empty three runs out of three (issue #346).

`ReminderService.observeTimer` now reports the whole lifecycle: scheduled, run, complete, threw,
cancelled. The hooks default to no-ops, so the production path never touches the test library.

The report also carries a usage note saying `java.util.Timer` is deprecated in favour of
`ScheduledExecutorService`. That one deliberately does not gate: it is advice, not a finding.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsTimerIssues` and run the test:

```
TIMER ISSUES DETECTED:
  Long-Running Task Warnings (task starvation):
    - reminder-timer: 8 task(s) exceeded 100 ms — starving subsequent tasks
      (all tasks share one thread in java.util.Timer)
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

The demonstration waits for its own reminder to fire, which is not politeness: the detector times
a task from run to complete, and the run is analysed as soon as the last body returns.

## The Fix

Replace `java.util.Timer` with `ScheduledExecutorService` (e.g.,
`Executors.newScheduledThreadPool(n)`), which uses multiple threads and
recovers from task exceptions without killing the scheduler. Always call
`shutdown()` in a `finally` block or `@AfterEach`.
