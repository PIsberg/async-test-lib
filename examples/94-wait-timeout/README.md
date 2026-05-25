# Example 94 — Wait Timeout

Demonstrates **WaitTimeoutDetector** catching `wait()` calls with no timeout.

## The Problem

`MessageRouter.waitForMessage()` calls `synchronized(lock) { lock.wait(); }` with no
timeout argument. If `deliver()` is never called — due to a race, a dropped message, or
a bug — the waiting thread blocks forever. In a test suite this silently hangs the build.

The fix is to supply a timeout (`lock.wait(timeoutMs)`) inside a `while` loop so the
thread can detect a stall and fail fast.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsInfiniteWait` in `MessageRouterTest`.
2. Run the test with Maven or Gradle:
   ```
   mvn test
   gradle test
   ```
3. **WaitTimeoutDetector** will report the infinite `wait()` on the message lock.

## The Fix

Replace `lock.wait()` with `lock.wait(5000)` inside a `while (!messageAvailable)` loop,
then throw or log if the timeout expires without a message arriving.
