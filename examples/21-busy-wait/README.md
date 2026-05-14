# Busy Wait Example

This example demonstrates a **real-world production bug** found in many worker services: **a tight spin loop that polls a queue instead of blocking**, wasting CPU and starving other threads.

## The Problem

The `SpinPollingWorker` drains a task queue with a tight `while (!queue.isEmpty())` loop.

**The Bug**: Every call to `process()` spins in a hot loop calling `isEmpty()` and `poll()` in rapid succession. When the queue is empty, the loop exits immediately. But when multiple threads compete for tasks, each thread burns its entire CPU slice polling — leaving no CPU for threads that have real work to do.

## Why It Happens

```java
// BUGGY CODE (SpinPollingWorker.java):
public String process() {
    String result = null;
    while (!taskQueue.isEmpty()) {  // ❌ tight spin — no yield, no park
        result = taskQueue.poll();
    }
    return result;
}
```

Each iteration of the spin loop makes two calls (`isEmpty` and `poll`) that return instantly when the queue is empty. The CPU core assigned to this thread is at 100% utilisation for the entire duration, even though it is producing no useful output.

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```bash
cd examples/21-busy-wait
mvn clean test
# Tests pass: no spin detected in single-threaded mode
```

The test passes because a single thread drains the queue quickly and returns. There are no other threads to starve, so the spin is invisible.

### 2. Run with @AsyncTest (DETECTS the spin loop)

Remove the `@Disabled` annotation from `testProcess_concurrent_detectsBusyWaiting()`:

```java
// Change this:
@Disabled("Remove @Disabled to see busy-waiting detected by BusyWaitDetector")
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectBusyWaiting = true)
void testProcess_concurrent_detectsBusyWaiting() { ... }
```

```bash
mvn clean test
# BusyWaitDetector reports: "Thread N: spun X iterations over Yms at SpinPollingWorker.processInstrumented"
```

With 8 threads all calling `processInstrumented()` simultaneously:
- Each thread records every iteration via `detector.recordLoopIteration()`
- Once a thread exceeds 10,000 consecutive iterations, the spin is flagged
- `BusyWaitDetector.analyzeBusyWaiting()` reports the spin location and CPU time wasted

## The Root Cause

`ConcurrentLinkedQueue` is a non-blocking collection. Its `isEmpty()` and `poll()` methods return immediately. A loop around them has no back-pressure: if the queue is empty the loop simply burns CPU doing nothing useful. Under concurrency:

1. 8 threads compete for the same shared queue
2. Each thread calls `recordLoopIteration()` on every poll
3. Threads exceed the 10,000-iteration spin threshold
4. `BusyWaitDetector` reports the spin intensity and wasted CPU milliseconds

## The Solution

Replace the spin loop with a blocking primitive that parks the thread at zero CPU cost:

```java
// FIXED CODE — use LinkedBlockingQueue.take():
private final LinkedBlockingQueue<String> taskQueue = new LinkedBlockingQueue<>();

public String processFixed() throws InterruptedException {
    // Parks the thread until an element is available — zero CPU while idle
    return taskQueue.take();
}
```

Alternative fixes:

```java
// Option 2: wait/notify inside synchronized block
public synchronized String processWithWait() throws InterruptedException {
    while (taskQueue.isEmpty()) {
        wait(); // releases the monitor and parks the thread
    }
    return taskQueue.poll();
}

// Option 3: CompletableFuture for async result delivery
// — eliminates the polling thread entirely
```

## Files in This Example

- **`SpinPollingWorker.java`** — Buggy service with a tight spin-poll loop
- **`SpinPollingWorkerTest.java`** — Tests that demonstrate the problem
  - `testProcess_singleThread_drainsQueue()` — Passes with @Test
  - `testProcess_concurrent_detectsBusyWaiting()` — Detects spin with @AsyncTest
  - `testProcess_fixedWithBlockingQueue_singleThread()` — Shows the correct pattern
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Single-threaded polling exits quickly and looks fine
2. **@AsyncTest finds the spin**: 8 threads × 50 invocations drives iteration counts past the spin threshold
3. **Spin loops waste CPU**: Every iteration is a wasted call with no useful output when the queue is empty
4. **Blocking beats spinning**: `LinkedBlockingQueue.take()` parks the thread at zero CPU cost while idle
5. **Always yield or block**: If a loop must poll, add `Thread.yield()` or a brief sleep to give other threads a chance
