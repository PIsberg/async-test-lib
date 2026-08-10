# 137 — Platform Thread-Per-Task

**Detector**: `PlatformThreadPerTaskDetector` (`DetectorType.PLATFORM_THREAD_PER_TASK`) · **Severity**: 🟡 High

## The bug

```java
for (String payload : payloads) {
    new Thread(() -> deliver(payload)).start();   // BUG: one OS thread per webhook
}
```

Each platform thread reserves an OS thread, roughly 1 MB of stack, and kernel scheduler load,
with a hard system-wide ceiling. Ten in a unit test is nothing; ten thousand an hour in
production fails at exactly the burst that matters.

## The fix

```java
for (String payload : payloads) {
    Thread.startVirtualThread(() -> deliver(payload));   // FIX
}
```

Thread-per-task is the right model for I/O-bound work — the thread kind was wrong, not the
pattern. For CPU-bound work, a pool sized to the cores is the right tool instead.

## What the detector observes

- `recordThreadCreated`, called once per created thread: at the churn threshold (default 16,
  lowered in the example test), platform threads that have already terminated read as per-task
  churn. Long-lived pool workers never trip it.
- `registerExecutor` runs one no-op probe task on a `newThreadPerTaskExecutor` to learn the
  actual thread kind; a platform factory behind a per-task executor is the finding.

## Run it

```bash
mvn test                 # or: gradle test
```
