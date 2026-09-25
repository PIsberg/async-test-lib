# Example 46 — Non-Daemon Background Thread

Demonstrates **DaemonThreadHygieneDetector** catching background threads that
are not marked as daemon threads, preventing orderly JVM shutdown.

## The Problem

`BackgroundWorker` spawns a thread for each unit of background work but
never calls `thread.setDaemon(true)`. Because user threads prevent JVM exit,
the application hangs after the main logic completes when any such thread is
still alive.

A plain `@Test` starts and finishes quickly and does not notice the orphaned
thread.

## Two things this example has to get right, and used to get wrong

**The thread has to still be running.** `DaemonThreadHygieneDetector` reports a non-daemon
thread that is *alive when the run is analysed*, and it is right to: a thread that has already
terminated cannot hold the JVM open, so flagging it would be flagging nothing. Before issue #346
this example started a thread that did a thousand additions and exited in microseconds, then
expected a report about it. The demonstration now starts pollers, which keep running until
`shutdown()` asks them to stop, because a poller is what a background worker usually is and the
only shape in which a missing daemon flag costs anything.

**The flag has to be the service's decision, not the runner's.** A thread inherits the daemon
flag of the thread that created it, and every worker `@AsyncTest` hands a body to is a daemon
thread: virtual threads always are, and the platform workers were made daemon so that a
deadlocked one could not hold the JVM open
([#479](https://github.com/PIsberg/async-test-lib/issues/479)). A poller built with
`new Thread(...)` inside the body is therefore already a daemon thread however wrong the service
is, and this detector reports nothing.

Setting `useVirtualThreads = false` used to fix that and was what
[#352](https://github.com/PIsberg/async-test-lib/issues/352) prescribed. It stopped working on
2026-09-03, when the platform workers became daemon as well, and this demonstration passed
silently for a fortnight until the weekly job caught it
([#730](https://github.com/PIsberg/async-test-lib/issues/730)).

`BackgroundWorker` now builds its pollers with `Executors.defaultThreadFactory()`, the factory
behind every JDK thread pool, which calls `setDaemon(false)` on each thread it hands back
whoever calls it. The missing daemon flag is the service's again, the demonstration fires on the
default runner, and the annotation no longer needs a thread mode at all. The runner still says
once per JVM, at INFO, what it cannot see:

```
runner.detector.inert test=... detector=DaemonThreadHygieneDetector
  reason="the runner's workers are daemon threads in both thread modes (#479) and a thread
  inherits the daemon flag of the thread that created it, so a thread the body constructs is
  already daemon, and this detector only reports non-daemon threads"
  hint="attach the agent with collections=true, which sees Thread.start and setDaemon, or
  record a thread whose factory sets the flag itself, such as
  Executors.defaultThreadFactory() or any JDK thread pool, or one created outside the body;
  otherwise read the report as 'not observed' rather than 'clean'"
```

The detector's javadoc and the `detectDaemonThreadHygiene` attribute say the same. With the agent
attached (`collections=true`) the bare `new Thread(...).start()` is judged too: the agent weaves
`Thread.start()` and `Thread.setDaemon(boolean)`, so the detector asks whether `setDaemon(true)`
was called rather than reading the inherited flag (#731), and the announcement is not made.

## How to Reproduce

1. Open `BackgroundWorkerTest.java`.
2. Remove the `@Disabled` annotation from `testStart_concurrent_detectsNonDaemonThread`.
3. Run the test:

```
DAEMON THREAD HYGIENE DETECTED (🟡 MEDIUM):
  - 'background-poller' (thread name='background-poller-async-119', id=128) is non-daemon and
    still alive at analysis time - non-daemon threads block JVM exit. Call
    thread.setDaemon(true) before start(), or ensure the thread terminates before the test ends.
    First recorded at: BackgroundWorkerTest.testStart_concurrent_detectsNonDaemonThread(...)
```

One line per body execution: 40 of them for `threads = 8, invocations = 5`.

`failOn = FailOn.LOW` is what turns that report into a failed run. `@AfterEach` then calls
`shutdown()`, without which the pollers would keep the JVM alive - the bug working as
advertised, and no way to run a build.

## The Fix

Call `thread.setDaemon(true)` before `thread.start()`, or manage background
work through a daemon-configured `ThreadFactory`.
