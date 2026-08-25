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

## The identity of the lambda is the whole thing

`StatefulLambdaDetector` keys on the **identity of the lambda instance**. A finding is one
instance that ran on more than one thread while mutating what it captured. A fresh lambda per
submission is a different object every time and produces nothing, which is correct: nothing was
shared.

That is exactly what this example used to get wrong. Its demonstration built a lambda inside the
test body, so 400 body executions made 400 lambdas with one thread each, and the report was empty
three runs out of three (issue #346). `TaskScheduler` now builds the task **once**, in its
constructor, and submits that same object over and over, which is both what the detector needs and
what the bug is.

`TaskScheduler.observeTask` is the seam that reports the executions and the mutations; it defaults
to no-ops, so the production path never touches the test library.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsStatefulLambda` and run the test:

```
STATEFUL LAMBDA SHARED ACROSS THREADS DETECTED:
  - 'counting-task' executed on 400 threads (...) with concurrent captured-state mutations: [...]
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

The 400 is worth a second look: it is the number of distinct thread ids, and `@AsyncTest` runs on
virtual threads by default, one per body execution. With `threads = 8, invocations = 50` that is
400, not 8. See issue #349.

## The Fix

Use `AtomicInteger` or `LongAdder` for the captured counter, or give each
submitted task its own independent state.
