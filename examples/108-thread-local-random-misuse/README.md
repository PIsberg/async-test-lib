# Example 108 — ThreadLocalRandom Misuse

Demonstrates **ThreadLocalRandomMisuseDetector** catching a cached `ThreadLocalRandom.current()` reference used from other threads.

Requires async-test-lib 1.7.0+.

## The Problem

`IdGenerator` caches the result of `ThreadLocalRandom.current()` in a `final` field at
construction time. `ThreadLocalRandom.current()` returns the generator that belongs to the
*calling* thread; storing that reference and reusing it from other threads defeats the
per-thread isolation the class is built on. Concurrent use of a single cached instance
from multiple threads corrupts and biases the produced output.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsCachedReferenceUsedAcrossThreads` in
   `IdGeneratorTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **ThreadLocalRandomMisuseDetector** will report that a single cached
   `ThreadLocalRandom` reference is used from a thread different from the one that
   obtained it.

## The Fix

Never store the result of `ThreadLocalRandom.current()`. Call it afresh per use on each
thread, e.g. `ThreadLocalRandom.current().nextLong()`, so every thread always uses its
own generator.
