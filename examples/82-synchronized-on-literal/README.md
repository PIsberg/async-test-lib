# Example 82 — Synchronized on String Literal

**Detector**: `SynchronizedOnLiteralDetector`  
**Flag**: `detectSynchronizedOnLiteral = true`

## The Problem

`ConfigService.set()` and `get()` use `synchronized("config-lock")` to protect
a `HashMap`. Because the JVM interns String literals, `"config-lock"` is always
the exact same `String` object across the entire JVM. Any other class that also
synchronizes on `"config-lock"` — even completely unrelated code — shares the
same lock, causing unexpected blocking and potential deadlocks.

This is sometimes called a "global lock" bug: the lock scope is accidentally
JVM-wide rather than instance-scoped.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsLiteralLock` and
run the test. `SynchronizedOnLiteralDetector` inspects every monitor acquired
via `recordMonitorAcquired()`, identifies String and other interned literals, and
reports them as violations.

## The Fix

Use a dedicated private final `Object` lock field:

```java
private final Object lock = new Object();
```

Or use `java.util.concurrent.locks.ReentrantLock` for more flexibility.
