# Example 83 — Thread Factory

**Detector**: `ThreadFactoryDetector`  
**Flag**: `detectThreadFactoryIssues = true`

## The Problem

`WorkerPoolService` creates its `ExecutorService` with
`Executors.newFixedThreadPool(4)` and no `ThreadFactory`. The default factory
produces threads with:
- Generic names like `pool-1-thread-1` — useless in thread dumps and profilers.
- Non-daemon status — the JVM will not exit until all pool threads terminate,
  making graceful shutdown harder and test hangs more likely.
- No `UncaughtExceptionHandler` — unhandled exceptions are silently swallowed
  in some configurations.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsDefaultFactory`
and run the test. `ThreadFactoryDetector` records the factory and threads it
creates, then checks names, daemon status, and exception handler presence in the
analysis report.

## The Fix

Supply a named, daemon `ThreadFactory`:

```java
ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "worker-pool-" + counter.incrementAndGet());
    t.setDaemon(true);
    t.setUncaughtExceptionHandler((thread, ex) -> log.error("Uncaught", ex));
    return t;
});
```
