# Example 46 — Non-Daemon Background Thread

Demonstrates **DaemonThreadHygieneDetector** catching background threads that
are not marked as daemon threads, preventing orderly JVM shutdown.

## The Problem

`BackgroundWorker` spawns a new `Thread` for each unit of background work but
never calls `thread.setDaemon(true)`. Because user threads prevent JVM exit,
the application hangs after the main logic completes when any such thread is
still alive.

A plain `@Test` starts and finishes quickly and does not notice the orphaned
thread. Under repeated concurrent invocations the accumulation of live
non-daemon threads becomes visible to `DaemonThreadHygieneDetector`.

## How to Reproduce

1. Open `BackgroundWorkerTest.java`.
2. Remove the `@Disabled` annotation from `testStart_concurrent_detectsNonDaemonThread`.
3. Run the test — `DaemonThreadHygieneDetector` will report threads that were
   started without daemon status.

## The Fix

Call `thread.setDaemon(true)` before `thread.start()`, or manage background
work through a daemon-configured `ThreadFactory`.
