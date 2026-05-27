# Example 76 — Stateful Lambda

**Detector**: `StatefulLambdaDetector`  
**Flag**: `detectStatefulLambda = true`

## The Problem

`TaskScheduler.scheduleCountingTasks()` creates a single `int[] count = {0}`
array and captures it in a lambda that is submitted to multiple threads. Each
thread increments `count[0]++` without synchronization. Because `int[]` is a
mutable container (not a primitive), the JVM permits its capture even though it
is mutated — the captured variable is the array reference, which is
effectively-final, but the array element is not.

Under concurrency:
- Two threads both read `count[0]` as `N`.
- Both write `N+1`.
- One increment is silently lost.

The final count is always less than `n`, but the exact value is
non-deterministic.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsStatefulLambda`
and run the test. `StatefulLambdaDetector` records each lambda execution and
captured-state mutation per thread, then flags lambdas executed concurrently
from multiple threads with captured mutations.

## The Fix

Use `AtomicInteger` or `LongAdder` for the captured counter, or give each
submitted task its own independent state.
