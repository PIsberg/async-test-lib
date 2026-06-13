# Example 109 — CompletableFuture Obtrude Abuse

This example demonstrates how `CompletableFutureObtrudeDetector` flags the concurrent use of `obtrudeValue(...)` or `obtrudeException(...)` on `CompletableFuture`.

## The Problem

`obtrudeValue` and `obtrudeException` forcibly change the outcome of a future, even if it is already completed.
Using these methods in concurrent pipelines breaks the immutability of completed stages and leads to race conditions,
making them highly discouraged outside testing or rare recovery scenarios.

## The Test

1. Under `src/test/java/se/deversity/asynctest/example/CompletableFutureObtrudeTest.java`, the test:
   - Sets up a shared `CompletableFuture`.
   - Starts concurrent tasks calling `future.obtrudeValue(...)`.
   - Records the obtrude calls using `AsyncTestContext.completableFutureObtrudeDetector().recordObtrude(...)`.
2. When `@AsyncTest` runs, `CompletableFutureObtrudeDetector` identifies that `obtrudeValue` has been abused.
