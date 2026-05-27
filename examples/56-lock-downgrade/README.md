# Example 56 — Lock Downgrade

Demonstrates `LockDowngradeDetector` catching an incorrect `ReentrantReadWriteLock`
downgrade that leaves a window where another thread can mutate shared data.

## The Problem

Correct lock downgrade requires acquiring the read lock **before** releasing the write
lock. The wrong order is:

```java
writeLock.unlock();        // gap opens here — another thread can write!
readLock.lock();           // too late: the data we just wrote may already be changed
```

The correct pattern is:
```java
readLock.lock();           // acquire read FIRST
writeLock.unlock();        // then release write — no gap
```

`DataStore.updateAndRead` releases the write lock first, creating a window where
another writer can modify the entry before the current thread reads it back.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`DataStoreTest`. The `LockDowngradeDetector` will report the incorrect downgrade
sequence on the `dataLock` lock.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLockDowngrade = true)
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
