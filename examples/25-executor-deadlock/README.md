# Example 25 — Executor Self-Deadlock

## The Problem

`ReportGenerationService.generateReport()` is submitted to a single-threaded
`ExecutorService`. Inside that task the code submits a data-collection subtask
to the **same** executor and immediately calls `Future.get()` on the result.
Because the only thread is already occupied running `generateReport()`, the
subtask sits in the queue forever. `get()` never returns and the executor hangs
permanently.

```
Thread-0 (busy running generateReport)
  └─ submits collectRawData() → queued behind Thread-0 (no free thread)
  └─ calls dataFuture.get() → blocks
                                ↑ deadlock
```

## Why This Happens

`ExecutorService` is a bounded resource. When every available thread is occupied
and one of those threads tries to obtain a result from a task that has not yet
started, you have a **self-deadlock**:

- The running task holds the only thread and is waiting for a sibling.
- The sibling cannot start because there is no free thread.
- Neither side can make progress.

The defect is invisible in single-threaded tests because the test thread calls
`generateReport()` *directly*, bypassing the executor, so the subtask can use
the idle executor thread without conflict.

## How to Reproduce

1. Open `ReportGenerationServiceTest`.
2. Remove `@Disabled` from `testGenerateReport_concurrent_detectsExecutorDeadlock`.
3. Run the test.

`ExecutorDeadlockDetector` will report, and `failOn = FailOn.LOW` will turn that report into a
failed run:

```
EXECUTOR SELF-DEADLOCK DETECTED:
  - report-executor: all 1 worker(s) are waiting on sibling tasks while 16 task(s) remain queued
  Fix: do not wait on sibling tasks from the same bounded executor
```

## How the Detector Is Fed

`ExecutorDeadlockDetector` is **recording-fed**. It needs four facts, and only one of them is
interesting: that a *running* task has started waiting on a sibling. Nothing outside the code can
supply that, which is why `ReportGenerationService.observeExecutor` installs the hooks inside the
service; they default to no-ops, so the production path never touches the test library.

The detector also has to be **the one the run owns**, from
`AsyncTestContext.executorDeadlockDetector()`. Before issue #346 this demonstration recorded a
lifecycle by hand into a locally constructed detector and asserted on it, without ever calling the
service. It proved the detector's arithmetic, which was never in doubt, and told the library
nothing, so `failOn` had no finding to gate on and enabling the demonstration left it green.

## One thing this example does that production would not

`generateReport()` waits with a timeout (`SUBTASK_TIMEOUT_MS`, 100ms) rather than an unbounded
`Future.get()`. The shape this is drawn from has no timeout and the thread is gone for the life of
the process. With a timeout, the request fails instead - every time, on every report - which is the
same bug wearing a different symptom, and it is the difference between a demonstration and a hung
build.

## The Solution

Submit the subtask to a **different** executor so the parent thread can block
without preventing the subtask from running:

```java
// Before (deadlock)
Future<String> data = executor.submit(() -> collectRawData(id));
String raw = data.get(); // blocks the only thread — subtask can never start

// After (safe)
Future<String> data = subtaskExecutor.submit(() -> collectRawData(id));
String raw = data.get(); // subtaskExecutor has free threads — works correctly
```

Alternatively, use non-blocking composition so no thread ever blocks at all:

```java
CompletableFuture.supplyAsync(() -> collectRawData(id), executor)
                 .thenApply(raw -> assembleReport(id, raw));
```

## Key Takeaways

- A thread calling `Future.get()` or `CompletableFuture.join()` is **blocked**
  — it cannot execute anything else until the future completes.
- When that blocked thread belongs to a bounded executor and the future's task
  is also submitted to that executor, you have a self-deadlock.
- Always submit blocking-wait parent tasks and their subtasks to **separate**
  executors, or redesign with non-blocking async pipelines.
- Virtual threads avoid this class of deadlock entirely because blocking a
  virtual thread unmounts it from the carrier thread, freeing the carrier for
  other work.
