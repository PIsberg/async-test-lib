# 136 — Virtual Thread Pooling

**Detector**: `VirtualThreadPoolingDetector` (`DetectorType.VIRTUAL_THREAD_POOLING`) · **Severity**: 🟡 High

## The bug

The service migrated to virtual threads by swapping the factory into its existing pool wiring:

```java
ExecutorService executor =
    Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());   // BUG
```

JEP 444 is explicit: virtual threads are cheap, single-task objects and must never be pooled.
This line keeps everything the migration was supposed to remove — concurrency stays capped at 4,
the four pooled workers are virtual threads that never terminate (so every `ThreadLocal` a task
leaves behind greets the next task on that worker), and submissions still queue behind slow
renders.

## The fix

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();   // FIX
```

One fresh virtual thread per task. If downstream capacity needs protecting, acquire a
`Semaphore` around the guarded operation — limit the operation, not the thread.

## What the detector observes

- `registerExecutor` probes the executor's `ThreadFactory` with one unstarted, discarded
  thread: a `ThreadPoolExecutor` manufacturing virtual threads is the finding.
- `recordTaskExecution`, called once per task, flags any virtual thread observed running a
  second task — reuse implies recycling upstream.

## Run it

```bash
mvn test                 # or: gradle test
```
