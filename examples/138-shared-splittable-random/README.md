# 138 — Shared SplittableRandom

**Detector**: `SharedSplittableRandomDetector` (`DetectorType.SHARED_SPLITTABLE_RANDOM`) · **Severity**: 🟡 High

## The bug

```java
private static final SplittableRandom RNG = new SplittableRandom(SEED);

long thinkTimeMillis() {
    return RNG.nextLong(50, 500);   // BUG: called from every worker thread
}
```

`SplittableRandom`'s own Javadoc says instances are not thread-safe, and the same holds for
the JEP 356 `java.util.random` implementations (`L64X128MixRandom`, `Xoshiro256PlusPlus`, …).
The state update is a plain read-modify-write; concurrent calls interleave it — duplicated
values, broken statistical guarantees, no exception. Unlike a shared `java.util.Random`
(thread-safe but contended, `SHARED_RANDOM`'s finding), there is no safety here to contend on.

## The fix

```java
SplittableRandom perWorker = root.split();   // FIX: each worker gets its own
```

`split()` derives an independent, statistically uncorrelated child — that is the designed use,
and it keeps per-seed reproducibility. `ThreadLocalRandom.current()` works for throwaway
randomness.

## What the detector observes

`registerGenerator` plus `recordAccess` per use; a tracked generator is reported when more than
one thread touched it and at least one of those accesses held no lock on the generator itself.
The `synchronized (generator)` idiom is recognised and stays silent; a `ReentrantLock` or a
private lock object is invisible and still produces a finding, and the report says so.
`java.util.Random` subclasses are excluded — they belong to
`SHARED_RANDOM`, `SHARED_SECURE_RANDOM`, and `THREAD_LOCAL_RANDOM_MISUSE`.

## Run it

```bash
mvn test                 # or: gradle test
```
