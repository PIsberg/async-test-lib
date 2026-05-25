# Example 49 — ExecutorService Never Shut Down

Demonstrates **ExecutorShutdownDetector** catching an `ExecutorService` that is
created but never shut down, leaking pooled threads with every test invocation.

## The Problem

`TaskRunnerService` creates a `FixedThreadPool` in its constructor and submits
tasks via `runTask()`, but never calls `shutdown()` or `shutdownNow()`. Each
service instance leaks four threads for the lifetime of the JVM. Under repeated
concurrent test invocations the thread count grows unboundedly.

A plain `@Test` creates one service instance and exits; the leak is invisible.
`ExecutorShutdownDetector` tracks every executor that submits work and checks
whether shutdown was called before the test round ends.

## How to Reproduce

1. Open `TaskRunnerServiceTest.java`.
2. Remove the `@Disabled` annotation from `testRunTask_concurrent_detectsMissingShutdown`.
3. Run the test — `ExecutorShutdownDetector` will report the leaked executor.

## The Fix

Close the executor in a `finally` block or implement `AutoCloseable` and call
`executor.shutdown()` followed by `awaitTermination()`.
