# Example 57 — Lock Leak

Demonstrates `LockLeakDetector` catching a `ReentrantLock` that is acquired in
`beginTransaction()` but never released when an exception occurs before
`commitTransaction()` is called.

## The Problem

Splitting lock acquire and release across separate methods is dangerous:

```java
void beginTransaction()  { lock.lock();   }   // acquires
void commitTransaction() { lock.unlock(); }   // releases

// If work throws, unlock is never called — all subsequent threads block forever
void execute(Runnable work) {
    beginTransaction();
    work.run();          // throws!
    commitTransaction(); // never reached
}
```

The safe pattern always uses `try/finally`:
```java
lock.lock();
try { work.run(); } finally { lock.unlock(); }
```

`TransactionService.execute` is missing the `try/finally` guard, so any failing
workload permanently holds the lock.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`TransactionServiceTest`. The `LockLeakDetector` will report the lock acquired
but never released.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLockLeaks = true)
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
