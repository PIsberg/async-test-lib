# Boxed Primitive Lock Example

This example demonstrates the **BoxedPrimitiveLockDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`SessionRegistry.registerSession()` retrieves an `Integer` from `AtomicInteger.get()` and
uses it as a `synchronized` lock object. The JVM interns all `Integer` values in the range
−128..127 (via `Integer.valueOf()` caching). This means **every class in the JVM that
synchronises on `Integer.valueOf(N)` for the same N shares the same monitor** — including
completely unrelated code in third-party libraries.

The same hazard applies to:
- `Boolean.TRUE` / `Boolean.FALSE`
- `Long` values in −128..127
- Interned `String` literals

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_registerSession_singleThread() {
    SessionRegistry registry = new SessionRegistry();
    assertEquals(1, registry.registerSession("alice")); // ✅ Passes
    assertEquals(2, registry.registerSession("bob"));   // ✅ Passes
}
```

Single-threaded code never races on the monitor, so the accidental sharing is invisible.
The counter increments correctly and the test passes.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectBoxedPrimitiveLock = true, timeoutMs = 5000)
void part2_detectBoxedPrimitiveLock() {
    var d = AsyncTestContext.boxedPrimitiveLockDetector();
    Integer lockObj = sessionCount.get(); // cached Integer
    d.recordLockAcquire(lockObj, Thread.currentThread(), "SessionRegistry:12");
    synchronized (lockObj) { ... } // flagged!
}
```

The detector reports:

```
BOXED PRIMITIVE LOCK DETECTED:
  - Thread 'Thread-3' synchronized on cached Integer(0) at [SessionRegistry:12]
    This is a JVM-global shared instance — other code may accidentally share this monitor.
    Fix: use a dedicated private final Object lock = new Object();
```

## Running the Example

```bash
cd examples/18-boxed-primitive-lock
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
private final Object lock = new Object(); // ✅ Dedicated, private, not shared

int registerSessionFixed(String userId) {
    synchronized (lock) {
        return sessionCount.incrementAndGet();
    }
}
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Unexpected contention | Unrelated code blocks on the same monitor — mysterious latency |
| Deadlock risk | Two unrelated classes lock on the same cached Integer in different orders |
