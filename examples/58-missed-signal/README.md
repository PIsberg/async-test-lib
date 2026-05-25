# Example 58 — Missed Signal

Demonstrates `MissedSignalDetector` catching a `notify()` fired before the
waiting thread has entered `wait()`, causing the waiting thread to block forever.

## The Problem

`Object.notify()` wakes **one** thread that is *currently* in `wait()`. If the
signalling thread calls `notify()` before the waiting thread has called `wait()`,
the signal is silently dropped:

```java
// Signaller thread                // Waiter thread
synchronized(monitor) {           // (not yet in wait)
    monitor.notify();             // signal lost!
}
                                  synchronized(monitor) {
                                      monitor.wait(); // blocks forever
                                  }
```

The fix is to use a shared boolean flag checked inside a `while` loop so a late
waiter sees the signal even if it arrives after `notify()`.

`WorkerCoordinator` has no such flag — `signal()` fires notify and `waitForSignal()`
enters wait with no memory of past notifications.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`WorkerCoordinatorTest`. The `MissedSignalDetector` will report the missed signal.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectMissedSignals = true)
void test_concurrent_detectsBug() { ... }
```

Run with Maven:
```
mvn test
```

Or with Gradle:
```
./gradlew test
```
