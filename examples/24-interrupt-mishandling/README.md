# Interrupt Mishandling Example

This example demonstrates a **real-world production bug** found in many worker services: **a swallowed `InterruptedException` that silently breaks cooperative thread cancellation**, making graceful shutdown unreliable.

## The Problem

The `BackgroundWorker` performs periodic work in a loop and is intended to stop when the owning thread is interrupted. `doWork()` calls `Thread.sleep(10)` and catches `InterruptedException` — but the catch block does nothing to restore the interrupted flag.

**The Bug**: When `Thread.sleep()` throws `InterruptedException`, the JVM clears the interrupted flag as part of throwing the exception. The catch block must either rethrow or call `Thread.currentThread().interrupt()` to restore the flag. Without this, any caller higher up the stack that checks `Thread.isInterrupted()` to decide whether to stop will find `false` — and keep running indefinitely.

## Why It Happens

```java
// BUGGY CODE (BackgroundWorker.java):
public void doWork() {
    try {
        Thread.sleep(10);
        workCount.incrementAndGet();
    } catch (InterruptedException e) {
        // ❌ interrupt flag is NOT restored — cancellation signal is lost
        // Thread.currentThread().interrupt() is intentionally missing
    }
}
```

The JVM clears the interrupted flag when it delivers the `InterruptedException`. After the catch block exits, `Thread.isInterrupted()` returns `false`. Any executor shutdown handler that interrupts worker threads to request cancellation will find no evidence of the interrupt, so the worker continues processing.

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```bash
cd examples/24-interrupt-mishandling
mvn clean test
# Tests pass: no interruption occurs in normal sequential execution
```

Sequential tests call `doWork()` directly. No interruption is ever issued, so the buggy catch block is never reached.

### 2. Run with @AsyncTest (DETECTS the swallowed interrupt)

Remove the `@Disabled` annotation from `testDoWork_concurrent_detectsInterruptSwallowing()`:

```java
@AsyncTest(threads = 6, invocations = 10, detectAll = false, detectInterruptMishandling = true)
void testDoWork_concurrent_detectsInterruptSwallowing() { ... }
```

```bash
mvn clean test
# InterruptMonitor reports:
#   "Thread 'xxx': ignored InterruptedException - BackgroundWorker.doWork — sleep interrupted but flag not restored"
```

With 6 threads each explicitly interrupting themselves before calling `doWork()`:
- `Thread.sleep()` throws `InterruptedException` immediately
- The catch block inside `doWork()` swallows it
- `interruptMonitor.recordIgnoredException()` records the swallowed interrupt
- `analyzeInterruptHandling()` reports all threads that silently discarded the interrupt

## The Root Cause

Java's cooperative cancellation model works as follows:

1. Code that wants to cancel a thread calls `thread.interrupt()`
2. The JVM sets the thread's interrupted flag
3. Blocking methods (`sleep`, `wait`, `join`, etc.) throw `InterruptedException` and **clear** the flag
4. The catch block **must** either rethrow or restore the flag with `Thread.currentThread().interrupt()`
5. If step 4 is skipped, the flag is gone and any shutdown logic that relies on it stops working

Under concurrent stress:
1. Each thread interrupts itself before sleeping
2. `Thread.sleep()` throws `InterruptedException` and clears the flag
3. The buggy catch block discards the exception silently
4. `InterruptMonitor` records all caught-but-not-restored interrupt events
5. The monitor report flags every thread that violated the protocol

## The Solution

Restore the interrupted flag in every `catch (InterruptedException)` block:

```java
// FIXED CODE — restore the flag:
public void doWork() {
    try {
        Thread.sleep(10);
        workCount.incrementAndGet();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // ✅ restore the flag
        // Optionally: also set running = false if this worker should stop
    }
}
```

Alternatively, propagate the exception:

```java
// ALTERNATIVE — propagate the exception:
public void doWork() throws InterruptedException {
    Thread.sleep(10);   // ✅ throws InterruptedException to the caller
    workCount.incrementAndGet();
}
```

## Files in This Example

- **`BackgroundWorker.java`** — Buggy worker that swallows `InterruptedException`
- **`BackgroundWorkerTest.java`** — Tests that demonstrate the problem
  - `testDoWork_singleThread_completesNormally()` — Passes with @Test
  - `testDoWork_concurrent_detectsInterruptSwallowing()` — Detects the swallowed interrupt with @AsyncTest
  - `testDoWorkFixed_restoresInterruptFlag()` — Shows the correct pattern
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Normal execution never interrupts, so the buggy catch is never hit
2. **@AsyncTest finds the swallow**: 6 threads × 10 invocations drives explicit interrupts through the buggy catch block
3. **Java's cancellation model is cooperative**: If you swallow the interrupt, shutdown handlers have no way to know the thread was interrupted
4. **Always restore or rethrow**: Every `catch (InterruptedException)` block must either call `Thread.currentThread().interrupt()` or rethrow
5. **Interrupted flag is cleared by the JVM**: Catching `InterruptedException` always clears it — you must manually restore it if you don't rethrow
