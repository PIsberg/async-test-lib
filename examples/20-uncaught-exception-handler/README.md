# Uncaught Exception Handler Example

This example demonstrates the **UncaughtExceptionHandlerDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`OrderProcessor.processAsync()` starts a `Thread` but never installs an
`UncaughtExceptionHandler`. When the thread throws (e.g., `IllegalArgumentException` for a
negative order ID) the exception is routed to the default `ThreadGroup` handler, which
**prints to stderr and discards it**. The submitting code has no way to observe the
failure — the thread pool silently replaces the dead thread and work appears to continue
normally.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_processOrder_singleThread() throws Exception {
    Thread worker = proc.processAsync(42); // valid order — no throw
    worker.join(1000);
    assertFalse(worker.isAlive()); // ✅ Passes — join() returns on normal exit too
}
```

`join()` returns when the thread terminates, whether by normal completion or by throwing.
The test does not verify that the work succeeded, only that the thread is done.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 2, detectUncaughtExceptionHandler = true, timeoutMs = 5000)
void part2_detectMissingHandler() {
    var d = AsyncTestContext.uncaughtExceptionHandlerDetector();
    Thread worker = new Thread(() -> { throw new RuntimeException("boom"); });
    // NOTE: no setUncaughtExceptionHandler() call
    d.recordThreadStart(worker);
    worker.start();
    try { worker.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    d.recordUncaughtException(worker, new RuntimeException("boom"));
}
```

The detector reports:

```
UNCAUGHT EXCEPTION HANDLER MISSING DETECTED:
  - Thread 'Thread-5' threw 'RuntimeException: boom' but had no custom
    UncaughtExceptionHandler — the exception was only printed to stderr.
    Fix: call thread.setUncaughtExceptionHandler(...) before start(), or
    use an ExecutorService that wraps tasks in try/catch.
```

## Running the Example

```bash
cd examples/20-uncaught-exception-handler
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
Thread processAsyncFixed(int orderId, AtomicReference<Throwable> errorCapture) {
    Thread t = new Thread(() -> {
        if (orderId < 0) throw new IllegalArgumentException("Invalid order: " + orderId);
    });
    t.setUncaughtExceptionHandler((th, ex) -> errorCapture.set(ex)); // ✅
    t.start();
    return t;
}
```

Or wrap in a `CompletableFuture` / `ExecutorService` which surfaces exceptions through
`Future.get()` and `CompletableFuture.exceptionally()`.

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Silent failure | Background tasks fail; the application state is partially updated |
| Stderr-only notification | Failures only appear in logs — no metric, no alert, no retry |
