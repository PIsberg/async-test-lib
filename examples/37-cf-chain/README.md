# Example 37 — CompletableFuture Chain (Fire-and-Forget)

Demonstrates **CompletableFutureChainDetector**: a CompletableFuture chain is
created but never joined. Exceptions are swallowed silently and the caller
never knows the pipeline failed.

## The Problem

`PipelineService.processAsync()` builds a `thenApply` chain that deliberately
throws a `RuntimeException` on every third invocation. The returned
`CompletableFuture` is never awaited by the caller — it is created and
immediately discarded. The exception propagates into the chain's internal
state but is never observed.

Under concurrent load many chains fail simultaneously while the caller
believes all pipelines succeeded.

## How to Reproduce

1. Remove `@Disabled` from `testPipeline_concurrent_detectsUnawaitedChain`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **CompletableFutureChainDetector** report listing
   futures that were created but never joined, and chains that completed
   exceptionally without a handler.

**Fix**: always `.join()` or `.get()` the returned future (or attach
`.exceptionally(ex -> ...)` to handle failures inline).
