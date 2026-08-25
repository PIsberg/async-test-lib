# Example 46 — Non-Daemon Background Thread

Demonstrates **DaemonThreadHygieneDetector** catching background threads that
are not marked as daemon threads, preventing orderly JVM shutdown.

## The Problem

`BackgroundWorker` spawns a new `Thread` for each unit of background work but
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

**The demonstration has to set `useVirtualThreads = false`.** A platform thread inherits the
daemon flag of the thread that created it, and virtual threads are always daemon. Under the
default runner every `new Thread(...)` started from a test body is therefore already a daemon
thread, and this detector has nothing to report however wrong the service is. Same service, same
detector, same recording: with the default runner the demonstration passes with an empty report,
and with `useVirtualThreads = false` it fails. That is issue #352.

## How to Reproduce

1. Open `BackgroundWorkerTest.java`.
2. Remove the `@Disabled` annotation from `testStart_concurrent_detectsNonDaemonThread`.
3. Run the test:

```
DAEMON THREAD HYGIENE DETECTED:
  - 'background-poller' (thread name='background-poller-async-61', id=65) is non-daemon and
    still alive at analysis time - non-daemon threads block JVM exit. Call
    thread.setDaemon(true) before start(), or ensure the thread terminates before the test ends.
    First recorded at: BackgroundWorkerTest.testStart_concurrent_detectsNonDaemonThread(...)
```

`failOn = FailOn.LOW` is what turns that report into a failed run. `@AfterEach` then calls
`shutdown()`, without which the pollers would keep the JVM alive - the bug working as
advertised, and no way to run a build.

## The Fix

Call `thread.setDaemon(true)` before `thread.start()`, or manage background
work through a daemon-configured `ThreadFactory`.
