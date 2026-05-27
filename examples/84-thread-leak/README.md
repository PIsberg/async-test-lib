# Example 84 — Thread Leak

**Detector**: `ThreadLeakDetector`  
**Flag**: `detectThreadLeaks = true`

## The Problem

`ConnectionHandlerService.handleConnection()` spawns a new `Thread` for every
incoming connection. Each thread loops indefinitely checking a `volatile boolean
running` flag — but `shutdown()` is never called in tests, so the flag stays
`true` and the threads keep running long after the test method returns.

Under concurrency load:
- Every test invocation adds one or more live threads to the JVM.
- After hundreds of invocations the OS-level thread limit is approached.
- Leaked threads hold stack memory and may write to shared state, causing
  false positives or missed detections in subsequent tests.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsThreadLeak` and
run the test. `ThreadLeakDetector` tracks every `recordThreadStart` call and
reports threads that are still alive at analysis time.

## The Fix

Always call `shutdown()` (which sets `running = false` and calls `join()`) in
an `@AfterEach` or a `try-finally` block so every connection thread terminates
before the test completes.
