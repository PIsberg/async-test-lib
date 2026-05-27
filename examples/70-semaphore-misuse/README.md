# Example 70 — Semaphore Misuse

Demonstrates **SemaphoreMisuseDetector**: a `RateLimiter` calls `acquire()`
then `release()` inline — without a `try/finally` guard. When the task throws
an exception the permit is never released. After enough failures all 5 permits
are drained and every subsequent caller blocks forever.

## The Problem

`RateLimiter.executeRequest()` acquires a permit with `sem.acquire()` then
calls `r.run()`. The `release()` call is placed after `r.run()` with no
`finally` block. If `r.run()` throws a `RuntimeException` the stack unwinds
past `release()`, permanently consuming one permit. After 5 failures the
semaphore is exhausted and the service hangs.

## How to Reproduce

1. Remove `@Disabled` from `testExecuteRequest_concurrent_detectsPermitLeak`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **SemaphoreMisuseDetector** report showing that
   acquire count exceeds release count — permits are permanently lost.

**Fix**: always call `release()` in a `finally` block:
```java
sem.acquire();
try { r.run(); } finally { sem.release(); }
```
