# 140 — CompletableFuture cancellation propagation

**Detector**: `CompletableFutureCancellationPropagationDetector` (`DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION`) · **Severity**: 🔴 High

## The bug

```java
CompletableFuture<Void> export = supplyAsync(() -> exporter.exportAll(50_000), pool);
CompletableFuture<String> view  = export.thenApply(this::render);

view.cancel(true);      // BUG: reads as "stop exporting". It does not.
```

`cancel` completes `view` with a `CancellationException` and stops there. It does not reach back
into `export`, it cannot stop a supplier already running on a pool, and `mayInterruptIfRunning`
is ignored outright — the JDK documents that a `CompletableFuture` never interrupts anything,
whichever value you pass.

So the user navigates away, the screen clears, and all 50,000 rows are still written.

## The fix

```java
CompletableFuture<String> view = new CompletableFuture<>();
CompletableFuture<Void> export = runAsync(
        () -> exporter.exportCooperatively(50_000, view::isCancelled),   // FIX: the stage asks
        pool);
```

Poll `isCancelled()`, or a `volatile boolean`, at the points where abandoning the work is safe.
When a real interrupt is needed, hold the `Future` the executor returned and cancel that —
`supplyAsync` hands you no reference to the running task.

`orTimeout()` and `completeOnTimeout()` have the same limit: they bound the *wait*, not the
*work*. The abandoned stage still runs to the end.

## What the detector observes

Bracket the stage body with `recordWorkStarted` / `recordWorkCompleted` and cancel through
`detector.cancel(...)`. Two findings:

- 🔴 **High** — a stage was recorded *finishing* after the cancel on the same pipeline. The work
  ran to the end regardless; the detector saw both events and their order. A cooperative stage
  records no completion after the cancel and stays silent, whether the cancel landed while it
  was running or before it was even dispatched. A stage that merely *started* after the cancel is
  counted in the message but is not a finding on its own: `cancel()` dequeues nothing, so a body
  already submitted begins regardless, and a cooperative one begins, checks, and returns.
- 🟡 **Medium** — `cancel(true)` was called. The flag has no effect on this type, so anything
  relying on an interrupt to unblock the stage is relying on something that will not happen.

## Run it

```bash
mvn test                 # or: gradle test
```
