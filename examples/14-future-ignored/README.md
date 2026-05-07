# Future Ignored Example

This example demonstrates the **FutureIgnoredDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`EventBus.publish()` calls `executor.submit(handler)` but discards the returned `Future`.
When the handler throws an exception, the exception is captured inside the `Future` but
never retrieved via `get()`. The calling code has no way to detect that the task failed —
the bus appears to be publishing events normally while silently swallowing every failure.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_eventPublished_singleThread() throws Exception {
    AtomicBoolean handled = new AtomicBoolean(false);
    bus.publish(() -> handled.set(true));
    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.SECONDS);
    assertTrue(handled.get()); // ✅ Passes — the task happened to succeed
}
```

With one thread and a successful handler the ignored Future causes no visible symptom.
The test never submits a failing handler, so the bug is invisible.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectFutureIgnored = true, timeoutMs = 5000)
void part2_detectIgnoredFuture() {
    var d = AsyncTestContext.futureIgnoredDetector();
    Future<?> f = executor.submit(() -> { throw new RuntimeException("handler failed"); });
    d.recordSubmit(f, "EventBus.publish", Thread.currentThread());
    // No d.recordInspect(f) — detector flags it at analysis time
}
```

The detector reports:

```
IGNORED FUTURE DETECTED:
  - Future submitted at [EventBus.publish] by 'Thread-1' was never inspected
    (get/isDone/cancel not called) — exceptions thrown by the task are silently lost.
    Fix: call Future.get() or log errors via CompletableFuture.exceptionally().
```

## Running the Example

```bash
cd examples/14-future-ignored
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
Future<?> publishTracked(Runnable handler) {
    return executor.submit(handler); // ✅ Caller receives the Future
}

// At call site:
Future<?> f = bus.publishTracked(handler);
try {
    f.get(5, TimeUnit.SECONDS); // ✅ Propagates exceptions
} catch (ExecutionException e) {
    log.error("Handler failed", e.getCause());
}
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Silent exception swallowing | Background tasks fail; the application state diverges invisibly |
| Lost error signals | Monitoring shows healthy submission rate while all tasks fail silently |
