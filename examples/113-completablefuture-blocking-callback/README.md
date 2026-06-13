# Example 113 — CompletableFuture Blocking Callback

This example demonstrates how `CompletableFutureBlockingCallbackDetector` flags blocking calls placed inside a asynchronous callback stage.

## The Problem

Callbacks like `thenApply` or `thenAccept` run on the common ForkJoinPool thread or a dedicated executor thread.
Executing a blocking call (such as `Thread.sleep()` or `Future.get()`) inside these callbacks blocks a pool worker thread,
potentially causing thread starvation and drastically reducing throughput of the entire asynchronous pipeline.

## The Test

1. Under `src/test/java/se/deversity/asynctest/example/CompletableFutureBlockingCallbackTest.java`, the test:
   - Sets up a `CompletableFuture` callback chain.
   - Enters the callback, calling `AsyncTestContext.cfBlockingCallbackDetector().recordEnterCallback("thenApply", thread)`.
   - Performs a blocking operation, calling `recordBlockingCall(thread, "Thread.sleep")`.
   - Exits the callback, calling `recordExitCallback(thread)`.
2. When `@AsyncTest` runs, `CompletableFutureBlockingCallbackDetector` flags the blocking call within the callback.
