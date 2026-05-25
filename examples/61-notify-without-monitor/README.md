# Example 61 — Notify Without Monitor

Demonstrates `NotifyWithoutMonitorDetector` catching a `queue.notify()` called
without holding the monitor on `queue`, which throws `IllegalMonitorStateException`
at runtime.

## The Problem

`Object.notify()` requires the calling thread to **own** the object's monitor
(i.e., be inside a `synchronized(obj)` block). Calling it without the monitor
is a programming error that the JVM enforces at runtime:

```java
// BUG: not inside synchronized(queue)
queue.notify(); // → throws IllegalMonitorStateException
```

`TaskQueue.add()` calls `queue.notify()` outside any `synchronized` block.
The consumer in `take()` correctly uses `synchronized(queue)` and `wait()`,
but the producer's notification is illegal and will always throw.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`TaskQueueTest`. The `NotifyWithoutMonitorDetector` will report the `notify()`
attempt made without holding the required monitor.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectNotifyWithoutMonitor = true)
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
