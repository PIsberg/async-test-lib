# Example 105 — Concurrent Map Check-Then-Act

Demonstrates **NonAtomicConcurrentMapUpdateDetector** catching a non-atomic `containsKey`-then-`put` on a `ConcurrentMap`.

Requires async-test-lib 1.7.0+.

## The Problem

`SessionCache.getOrCreate()` checks whether a key is present and then puts a freshly-created
session if it is absent. A `ConcurrentMap` makes each single operation atomic, but the
`containsKey()`-then-`put()` compound operation is **not** atomic. Two threads can both observe
the key as absent and both `put()` a new session, so one session silently overwrites the other —
losing a session and any state tied to it.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsCheckThenAct` in `SessionCacheTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **NonAtomicConcurrentMapUpdateDetector** will report multiple threads performing a
   non-atomic check-then-act against the same `ConcurrentMap` instance.

## The Fix

Replace the check-then-act with an atomic compound operation — `computeIfAbsent`,
`putIfAbsent`, or `merge`:

```java
return sessions.computeIfAbsent(userId, id -> "session-" + System.nanoTime());
```
