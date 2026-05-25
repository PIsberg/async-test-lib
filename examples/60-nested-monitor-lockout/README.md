# Example 60 — Nested Monitor Lockout

Demonstrates `NestedMonitorLockoutDetector` catching a `wait()` called while
holding a second monitor, creating a wait/notify deadlock.

## The Problem

Nested monitor lockout occurs when:
1. Thread A holds monitor X and calls `wait()` on monitor Y.
2. Thread B holds monitor Y and needs monitor X to send the notification.

Neither thread can proceed:

```
Thread A: synchronized(lockA) { lockB.wait(); }  // holds A, waits on B
Thread B: synchronized(lockB) { lockA.notifyAll(); } // holds B, needs A → deadlock
```

`ProducerConsumerService` exhibits exactly this pattern: `produce()` synchronizes
on `lockA` and waits on `lockB`; `consume()` synchronizes on `lockB` and calls
`notifyAll` on `lockA`. The two monitors create a circular dependency.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`ProducerConsumerServiceTest`. The `NestedMonitorLockoutDetector` will report
the blocked monitor acquisition while holding another monitor.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectNestedMonitorLockout = true)
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
