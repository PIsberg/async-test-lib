# Example 88 — Timer Misuse

**Detector**: `TimerDetector`  
**Flag**: `detectTimerIssues = true`

## The Problem

`ReminderService` uses a single `java.util.Timer` instance for all reminders, and `Timer` has
exactly one background thread. Two things follow.

**Everything queues.** A task that takes 150ms delays every task that falls due behind it,
whatever delay each was scheduled with. Eight reminders that all want to fire now fire one after another
over the next second and a bit. `test_oneThreadMeansTheyQueue` pins that with no detector
involved: three 150ms tasks take at least 450ms.

**A task that throws takes the scheduler with it.** An uncaught exception in a `TimerTask` kills
the timer thread, and `Timer` responds by cancelling every task still scheduled, silently. The
first anybody hears about it is the next `schedule()` call throwing `IllegalStateException`.

## What the detector reports

Those two, and only those two: `hasIssues()` gates on `timerThreadFailures` and
`starvedTaskWarnings`. It does **not** report a timer that was never cancelled, which is what
this example used to claim. Its demonstration registered the timer, recorded a schedule, and
stopped, so the detector never saw a task run, never timed one, and never heard about an
exception. The report was empty three runs out of three (issue #346).

Starvation is observed rather than inferred from a duration (#575). The run hook hands the detector
the `TimerTask` itself, from inside `run()`, so it can read `scheduledExecutionTime()`: a reminder
is reported when it fell due while another reminder still held the timer thread. A slow reminder
with nothing due behind it starves nobody and is silent, however long it ran;
`testTimerDetector_slowReminderAlone_isSilent` pins that. The detector used to call any task over
100 ms long-running, which a GC pause or a loaded CI runner could trip on correct code.

`ReminderService.observeTimer` reports the whole lifecycle: scheduled, run, complete, threw,
cancelled. The hooks default to no-ops, so the production path never touches the test library.

The report also carries a usage note saying `java.util.Timer` is deprecated in favour of
`ScheduledExecutorService`. That one deliberately does not gate: it is advice, not a finding.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsTimerIssues` and run the test:

```
TIMER ISSUES DETECTED:
  Starved Tasks (fell due while another task held the thread):
    - reminder-timer: 7 task(s) fell due while another task held the timer's only thread,
      and waited for it: 'reminder-2' fell due 9 ms into a 150 ms run of 'reminder-1' ...
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

The eight bodies schedule their reminders 10 ms apart, so every reminder after the first falls due
inside an earlier one's 150 ms run; the exact count and offsets vary with the interleaving. The
demonstration waits for its own reminder to fire, which is not politeness: a reminder is judged
when it runs, and the run is analysed as soon as the last body returns.

## The Fix

Replace `java.util.Timer` with `ScheduledExecutorService` (e.g.,
`Executors.newScheduledThreadPool(n)`), which uses multiple threads and
recovers from task exceptions without killing the scheduler. Always call
`shutdown()` in a `finally` block or `@AfterEach`.
