# Interrupt Swallowing Example

This example demonstrates the **InterruptSwallowingDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`TaskRunner.runWithSleep()` catches `InterruptedException` but silently discards it — neither
rethrowing nor calling `Thread.currentThread().interrupt()` to restore the interrupted state.

The consequence: any code up the call stack that checks `Thread.isInterrupted()` (e.g., an
executor shutdown handler or a cooperative-cancellation loop) will never see the interrupt.
The thread simply continues running as if it was never cancelled.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_runnerCompletes_singleThread() {
    TaskRunner runner = new TaskRunner();
    assertDoesNotThrow(() -> runner.runWithSleep(1));
    // ✅ Passes — the sleep completes normally; the interrupt path is never exercised
}
```

No thread interrupts the runner in a single-threaded test, so the buggy `catch` block is
never reached. All assertions pass and the problem is invisible.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectInterruptSwallowing = true, timeoutMs = 5000)
void part2_detectInterruptSwallowing() {
    var d = AsyncTestContext.interruptSwallowingDetector();
    try {
        Thread.sleep(10);
    } catch (InterruptedException e) {
        d.recordCatch(Thread.currentThread(), "TaskRunner.runWithSleep", false);
        // false = interrupt flag NOT restored
    }
}
```

The detector reports:

```
INTERRUPT SWALLOWING DETECTED:
  - Thread 'Thread-3' caught InterruptedException at [TaskRunner.runWithSleep]
    without restoring the interrupt flag — callers cannot observe the cancellation.
    Fix: add Thread.currentThread().interrupt() in the catch block, or rethrow.
```

## Running the Example

```bash
cd examples/11-interrupt-swallowing
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# To see the detector fire, upgrade pom.xml to 0.10.0 and change Part 2
# from @Test to @AsyncTest (see comments inside the test file)
```

## The Fix

```java
void runWithSleepFixed(long ms) {
    try {
        Thread.sleep(ms);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // ✅ Restore the flag
    }
}
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Silent cancellation suppression | Executors cannot be shut down cleanly; cooperative-cancel loops spin forever |
| Lost wake-up | Blocking calls that should unblock on interrupt keep sleeping |
