# Busy Wait Example

This example demonstrates a **real-world production bug** found in many worker services: **a tight
spin loop that polls a queue instead of blocking**, wasting CPU and starving other threads.

## The Problem

`SpinPollingWorker.awaitTask()` waits for the next task by polling a `ConcurrentLinkedQueue` in a
tight loop. `poll()` on a non-blocking queue returns instantly whether or not there is anything
there, so a worker that comes up empty just asks again, and again, for its whole spin budget. The
core it is on stays at 100% while it produces nothing.

Adaptive locks really do spin before parking, which is why this shape survives review. The bug is a
spin budget large enough to matter with no fallback to a blocking wait.

## Why It Happens

```java
// BUGGY CODE (SpinPollingWorker.java):
public String awaitTask(long maxSpins, ...) {
    for (long spins = 0; spins < maxSpins && running; spins++) {
        String task = taskQueue.poll();
        if (task != null) { return task; }
        // no Thread.onSpinWait(), no Thread.yield(), no park - just poll again
    }
    return null;
}
```

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```bash
cd examples/21-busy-wait
mvn clean test
# Tests pass: one thread that submits a task and then asks for one finds it on the first poll
```

The loop exits after a single iteration, far below the detector's 10,000-iteration threshold.
Nothing about the code looks expensive until a second worker arrives.

### 2. Run with @AsyncTest (DETECTS the spin loop)

Remove the `@Disabled` annotation from `testProcess_concurrent_detectsBusyWaiting()` and run:

```bash
mvn clean test
```

Eight threads share four tasks, so at least four of them poll an empty queue for the whole budget:

```
BUSY-WAITING DETECTED:

Spin loops:
  - Thread 70: spun 25 000 iterations over 1ms at
    se.deversity.asynctest.example.service.SpinPollingWorker.awaitTask(SpinPollingWorker.java:82)
```

`failOn = FailOn.LOW` turns that finding into a failed run. Without it the report is printed and
the test stays green, which is the trap issue #346 was opened for.

## How the Detector Works

`BusyWaitDetector` is **recording-fed**: it counts what the code under test hands it. The two
`Runnable` hooks on `awaitTask` are the seam - one per poll, one as the loop exits - and they are
plain `java.util.function` types, so the production path never imports the test library.

The count is per thread and it resets at every loop exit, so what the detector reports is one
uninterrupted spin, not a total across the run. A thread that passes 10,000 iterations before its
loop exits produces a finding; a thread that finds work on the first poll produces nothing.

The detector has to be **the one the run owns**, from `AsyncTestContext.busyWaitDetector()`. This
demonstration used to record into a locally constructed `BusyWaitDetector`, which the library never
reads, so `failOn` had nothing to gate on however hard the threads spun.

## The Solution

Replace the spin loop with a blocking primitive that parks the thread at zero CPU cost:

```java
// FIXED CODE - use LinkedBlockingQueue.take():
private final LinkedBlockingQueue<String> taskQueue = new LinkedBlockingQueue<>();

public String awaitTaskFixed() throws InterruptedException {
    // Parks the thread until an element is available - zero CPU while idle
    return taskQueue.take();
}
```

Alternative fixes:

```java
// Option 2: wait/notify inside synchronized block
public synchronized String awaitTaskWithWait() throws InterruptedException {
    while (taskQueue.isEmpty()) {
        wait(); // releases the monitor and parks the thread
    }
    return taskQueue.poll();
}

// Option 3: a bounded spin that then blocks - what an adaptive lock does.
// Spin a few dozen times with Thread.onSpinWait(), then fall back to take().
```

## Files in This Example

- **`SpinPollingWorker.java`** - Buggy service with a tight spin-poll loop
- **`SpinPollingWorkerTest.java`** - Tests that demonstrate the problem
  - `testAwaitTask_taskAvailable_returnsOnFirstPoll()` - passes with `@Test`, one iteration
  - `testAwaitTask_emptyQueue_spinsTheWholeBudget()` - pins the bug: 25,000 polls, no back-off
  - `testAwaitTask_spinIsVisibleToTheDetector()` - the detector's positive direction
  - `testAwaitTask_noSpin_isSilent()` - and its negative direction, so it is not flagging every loop
  - `testProcess_concurrent_detectsBusyWaiting()` - detects the spin with `@AsyncTest`
  - `testProcess_fixedWithBlockingQueue_singleThread()` - shows the correct pattern
- **`pom.xml`** - Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: a single worker always finds its task on the first poll
2. **@AsyncTest finds the spin**: more workers than tasks means somebody polls an empty queue
3. **Spin loops waste CPU**: every iteration is a wasted call producing no output
4. **Blocking beats spinning**: `LinkedBlockingQueue.take()` parks the thread at zero CPU cost
5. **If a loop must poll, bound it and then block**: spin briefly with `Thread.onSpinWait()`, then
   fall back to a blocking wait rather than burning the core
