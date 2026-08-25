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

## Which of the two findings this demonstrates

`WeakReferenceRaceDetector` reports two things:

1. **A `get()` result used without a null check.** This needs nothing from the collector. It is a
   property of the code, and `process()` has it.
2. **A reference that returned non-null on one thread and null on another**, which means the
   referent really was collected during the run.

This example used to aim at the second, and the test held a strong reference to the payload in a
field, so the referent could never be collected. Empty report, three runs out of three
(issue #346). It now aims at the first, recorded from inside `process()` at the point where the
unchecked use happens, which is deterministic and needs no GC pressure.

You can still see the crash itself, without waiting for a collector:
`testProcess_referentClearedBetweenTheTwoGets_throwsNullPointerException` clears the
`WeakReference` from inside the hook that fires after the first `get()`, which is exactly what a
GC cycle landing there would do, and `process()` throws.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsWeakReferenceRace` in
   `WeakCacheEntryTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. It fails:

```
WEAK REFERENCE RACE DETECTED:
  ERROR - 'counter-cache': WeakReference.get() result used without null check on thread(s)
          (async-test-worker-0, ...) — the referent may be collected at any point
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

`invocations` is 1 here only to keep that thread list readable: the detector joins every
recording thread's name into one line with no deduplication, so 400 executions produce a single
9 KB line. That is issue #351, not a property of the example.

## The Fix

Assign the result of `ref.get()` to a local variable once and null-check that variable:

```java
Payload val = ref.get();
if (val != null) { val.doWork(); }  // local var is not GC'd
```
