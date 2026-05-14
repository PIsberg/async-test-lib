# Livelock Example

This example demonstrates **detection of a livelock** in a distributed lock retry
service using the `LivelockDetector`.

## The Problem

`PoliteRetryLockService.acquireLock(nodeId)` retries lock acquisition immediately
on contention, yielding with `Thread.yield()` and no sleep. When multiple nodes
simultaneously detect contention, they all back off and retry at exactly the same
moment — creating a symmetric cycle that repeats indefinitely.

```
Thread 1: CAS(null → node-1) FAILS → yield → retry → CAS FAILS → yield → retry …
Thread 2: CAS(null → node-2) FAILS → yield → retry → CAS FAILS → yield → retry …
Thread 3: CAS(null → node-3) WINS  → critical section → release
Thread 1: CAS(null → node-1) FAILS → yield → retry …  (immediately re-contested)
Thread 2: CAS(null → node-2) FAILS → yield → retry …
```

All threads are continuously `RUNNABLE` — they are not blocked or waiting — yet
no thread accumulates useful work because the lock is never held long enough for
a second winner to emerge before all losers flood back in.

## Livelock vs Deadlock

| | Deadlock | Livelock |
|---|---|---|
| Thread state | `BLOCKED` | `RUNNABLE` |
| CPU usage | Near zero | High (busy-spinning) |
| Appears to make progress | No | Appears active, but doesn't |
| Detectable by timeout alone | Yes | No — timeout fires but threads look healthy |

Because livelock threads are `RUNNABLE`, `ThreadMXBean.findDeadlockedThreads()`
returns nothing. `LivelockDetector` catches it by analysing patterns over time:
rapid state transitions, CPU growth without work completion, and no-progress threads.

## Why Sequential Tests Miss This Bug

```java
@Test
void testAcquireLock_singleThread_succeeds() {
    boolean acquired = service.acquireLock("node-1");
    assertTrue(acquired);  // ✅ Passes — no contention, first CAS always wins
}
```

With one thread there is never a competing CAS. The single node acquires the lock
immediately and completes without retrying.

## How to Reproduce

### 1. Run with @Test (PASSES)

```bash
cd examples/07-livelock
mvn clean test
# ✅ All @Test methods pass
```

### 2. Run with @AsyncTest (LIVELOCK DETECTED)

Remove `@Disabled` from `testAcquireLock_concurrent_detectsLivelock()` and run:

```bash
mvn clean test
# Many invocations fail (MAX_RETRIES exhausted) and LivelockDetector fires
```

Expected output:

```
LIVELOCK / STARVATION ISSUES DETECTED:

Livelock Candidates (rapid state changes):
  - async-test-thread-3
  - async-test-thread-5
  → Threads keep changing state without making progress

Threads with No Progress:
  - async-test-thread-1
  - async-test-thread-2

Why: Livelock threads appear active (CPU time accumulates) but make no real
progress — they keep reacting to each other's state changes in a tight cycle,
burning CPU indefinitely without completing any work.

Fix:
  - Livelock: introduce randomised back-off between retries — e.g.
    Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50)) — so threads
    yield to each other rather than thrashing in lock-step
```

## How the Detector Works

`LivelockDetector` is a **Phase 1 detector** — no manual instrumentation calls are
needed in the test body. The framework automatically calls
`livelockDetector.captureSnapshot()` at the end of each invocation round.

Each snapshot records all JVM thread states (via `ThreadMXBean.dumpAllThreads()`)
and CPU times. After all invocation rounds complete, the detector analyses the
history for:

1. **Rapid state cycling**: thread changes state 5+ times in 10 consecutive snapshots
2. **Starvation**: thread is always `BLOCKED` or `WAITING` and CPU time never grows
3. **No progress**: CPU time did not increase and thread state did not change

Under a livelock, threads show rapid state cycling (`RUNNABLE` → brief yield →
`RUNNABLE` repeatedly) and CPU time growing without work completing.

## The Fix

Introduce randomised exponential back-off before each retry:

```java
// FIXED: exponential back-off with jitter breaks the symmetry
public boolean acquireLockFixed(String nodeId) throws InterruptedException {
    long delayMs = 1L;

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        if (lockHolder.compareAndSet(null, nodeId)) {
            performCriticalWork(nodeId);
            lockHolder.set(null);
            acquisitionCount++;
            return true;
        }

        // Different wait times mean not all nodes retry simultaneously
        Thread.sleep(delayMs + (long)(Math.random() * delayMs));
        delayMs = Math.min(delayMs * 2, 100L);
    }
    return false;
}
```

The randomised delay ensures at least one node is sleeping while another holds the
lock. When the holder releases, a subset of contenders retries — not all of them
simultaneously — so one makes progress while the rest wait.

## Files in This Example

- **`PoliteRetryLockService.java`** — Buggy lock service with zero-delay retry + fixed version
- **`PoliteRetryLockServiceTest.java`** — Sequential `@Test` methods that pass + `@AsyncTest`
  that triggers LivelockDetector
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib 1.3.0)

## Key Takeaways

1. **Livelocks are invisible to standard timeouts**: threads appear active and healthy
   from a monitoring perspective. `LivelockDetector`'s snapshot-based analysis is the
   only automated way to catch the pattern.
2. **Zero-delay retry loops are dangerous under contention**: always add back-off in any
   retry loop that competes for a shared resource.
3. **Randomness breaks symmetry**: a deterministic back-off (e.g. fixed `5 ms`) can
   still cause livelock if all threads pick identical delays. Jitter is essential.
