# Example 27 — CountDownLatch Misuse

## The Problem

`ServiceInitializer.initialize()` starts N microservices concurrently and uses
a `CountDownLatch(serviceCount)` to wait until all have reported in — whether
successfully or not. The coordination code looks plausible at first glance:

```java
try {
    startService(serviceId);
} catch (Exception e) {
    latch.countDown(); // BUG: called on failure path
    System.err.println("Service " + serviceId + " failed");
} finally {
    latch.countDown(); // called unconditionally
}
```

When `startService()` throws, the catch block fires **and** the finally block
fires, resulting in **two** `countDown()` calls for that one task. The latch
reaches zero before all services have completed, and the caller proceeds to
serve traffic while some services may still be initializing.

## Why This Happens

`CountDownLatch` counts are non-negative — calling `countDown()` beyond zero
is silently ignored, so there is no exception to alert you. In environments
where failures are uncommon (e.g. tests with mocked dependencies), the double
call is never triggered and the bug lies dormant until a service restart occurs
under load.

## How to Reproduce

1. Open `ServiceInitializerTest`.
2. Remove `@Disabled` from `testInitialize_concurrent_detectsExtraCountDowns`.
3. Run the test.

`LatchMisuseDetector` will report:

```
LATCH MISUSE DETECTED:
  - service-startup-latch: countDown() called 4 times for initial count 3
  Fix:
    - Ensure every thread that participates in the latch calls countDown() exactly once, even on exception paths
    - Use try/finally to guarantee countDown() is called: try { doWork(); } finally { latch.countDown(); }
```

`failOn = FailOn.LOW` turns that report into a failed run.

## How the Detector Is Fed

`LatchMisuseDetector` is **recording-fed**. It compares countDown() calls against the count the
latch was built with, so it has to be told about the construction, about every call, and about the
await. `ServiceInitializer.observeLatch` reports all three from where they happen; the hooks
default to no-ops, so the production path never touches the test library.

The detector also has to be **the one the run owns**, from
`AsyncTestContext.latchMisuseDetector()`. Before issue #346 this demonstration recorded four
countDown() calls by hand into a locally constructed detector and asserted on the result, without
ever calling `ServiceInitializer`. It proved the detector's arithmetic, which was never in doubt,
and told the library nothing, so `failOn` had no finding to gate on and enabling the demonstration
left it green.

## The Solution

Move `countDown()` **exclusively** to the `finally` block. This guarantees
exactly one call per task regardless of whether the task succeeds or fails:

```java
// Before (misuse)
try {
    startService(id);
} catch (Exception e) {
    latch.countDown(); // extra call on failure
} finally {
    latch.countDown(); // always fires — double-decrement on failure
}

// After (correct)
try {
    startService(id);
} catch (Exception e) {
    recordFailure(id, e);
} finally {
    latch.countDown(); // exactly once per task, always
}
```

## Key Takeaways

- `CountDownLatch.countDown()` past zero is a no-op — there is no guard or
  exception to tell you that you called it too many times.
- The only safe pattern is `try { doWork(); } finally { latch.countDown(); }`.
  Never call `countDown()` in a `catch` block when a `finally` also calls it.
- A latch that reaches zero prematurely causes the `await()` caller to unblock
  before all participants have finished — a silent correctness bug.
- If you need retries or reusable barriers, prefer `CyclicBarrier` or
  `CompletableFuture`-based coordination over `CountDownLatch`.
