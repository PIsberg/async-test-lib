# Example 97 — Weak Reference Race

Demonstrates **WeakReferenceRaceDetector** catching a TOCTOU race on a `WeakReference`.

## The Problem

`WeakCacheEntry.process()` calls `ref.get()` once to check for null and then calls
`ref.get()` a second time to use the referent. Between the two calls, the GC can collect
the weakly-reachable object, causing the second `get()` to return `null` — resulting in
a `NullPointerException` at runtime.

```java
if (ref.get() != null) {       // first get() — non-null here …
    ref.get().doWork();         // second get() — null! GC collected it between calls
}
```

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsWeakReferenceRace` in
   `WeakCacheEntryTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **WeakReferenceRaceDetector** will report the unsafe double-get pattern.

## The Fix

Assign the result of `ref.get()` to a local variable once and null-check that variable:

```java
Payload val = ref.get();
if (val != null) { val.doWork(); }  // local var is not GC'd
```
