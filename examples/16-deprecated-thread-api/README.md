# Deprecated Thread API Example

This example demonstrates the **DeprecatedThreadApiDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`WorkerManager.forceStop()` calls `Thread.stop()` to forcibly terminate a worker thread.
`Thread.stop()` works by throwing `ThreadDeath` into the target thread at an arbitrary
execution point — releasing every monitor the thread holds on the way out. Any shared
object that the thread was mutating is left in an inconsistent state. The API was
deprecated in Java 1.2 and its implementation was removed in Java 20.

Other flagged APIs: `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`,
`Thread.countStackFrames()` — all carry similar dangers or are simply non-functional
in modern JVMs.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_workerStarted_singleThread() throws Exception {
    mgr.startWorker();
    assertNotNull(mgr.worker); // ✅ Passes — tests start-up only, uses safe cancel path
}
```

The test exercises the happy path without ever calling `forceStop()`, so the dangerous
API is never reached.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 2, detectDeprecatedThreadApi = true, timeoutMs = 5000)
void part2_detectDeprecatedApi() {
    var d = AsyncTestContext.deprecatedThreadApiDetector();
    d.recordApiUse("Thread.stop", Thread.currentThread());
    // worker.stop(); // would be flagged immediately
}
```

The detector reports:

```
DEPRECATED THREAD API DETECTED:
  - 'Thread-1' called 'Thread.stop' — this method is unsafe and removed in Java 20+.
    Fix: use cooperative cancellation (volatile flag + interrupt) instead.
```

## Running the Example

```bash
cd examples/16-deprecated-thread-api
mvn clean test
# ✅ Tests pass — the forceStop() path is not exercised

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
void cancelGracefully() throws InterruptedException {
    if (worker != null) {
        cancelled.set(true);   // ✅ Signal cooperative exit
        worker.interrupt();    // ✅ Wake up sleeping/waiting calls
        worker.join(500);      // ✅ Wait for clean shutdown
    }
}
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Corrupted shared state | Objects mutated by the stopped thread are left half-updated |
| `UnsupportedOperationException` | `Thread.stop()` throws on Java 20+ — not just deprecated |
