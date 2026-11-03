# Livelock Example

This example measures the cost of a zero-delay retry in a distributed lock service, and says what
`LivelockDetector` does and does not report about it.

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
| What a round timeout shows | Workers `BLOCKED` or `WAITING` | Workers still `RUNNABLE` |

Because livelock threads are `RUNNABLE`, `ThreadMXBean.findDeadlockedThreads()`
returns nothing. `LivelockDetector` catches it from the one thing that shows it: the body did not
finish. When a round times out, it reads the state of each worker still in its body.

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

### 2. Why there is no @AsyncTest demonstration here

`acquireLock` gives up after 200 attempts, so a round ends and the detector correctly has nothing to
say: what this design loses is the work the other nodes threw away, which is a throughput bug rather
than a stall. The tests measure that directly. A retry loop with no such limit does draw the finding,
which `agent_retryLoop_permitNeverReleased` in `corpus-eval` holds against its twin.

## How the Detector Works

No manual instrumentation is needed in the test body. The runner marks each worker in and out of the
body, and when a round times out, before it interrupts anyone, it asks the detector to sample the
workers still inside through `Thread.getState()`, on platform and virtual workers alike:

1. **Livelock candidate**: `RUNNABLE` in any sample, which is a spin, a retry that never succeeds, a
   long computation or a blocking native call
2. **Starvation**: `BLOCKED` or `WAITING` in every sample
3. **No progress**: anything else, such as a retry loop asleep in `TIMED_WAITING`

A round that finishes is never reported. The only threshold is the `timeoutMs` the test chose.

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
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib 1.7.0-RC2)

## Key Takeaways

1. **Livelocks are invisible to standard timeouts**: threads appear active and healthy
   from a monitoring perspective. `LivelockDetector`'s snapshot-based analysis is the
   only automated way to catch the pattern.
2. **Zero-delay retry loops are dangerous under contention**: always add back-off in any
   retry loop that competes for a shared resource.
3. **Randomness breaks symmetry**: a deterministic back-off (e.g. fixed `5 ms`) can
   still cause livelock if all threads pick identical delays. Jitter is essential.
