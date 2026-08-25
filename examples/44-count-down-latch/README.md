# Example 44 — CountDownLatch Misuse

Demonstrates **CountDownLatchDetector** catching a latch that never reaches zero
because one code path skips the `countDown()` call.

## The Problem

`StartupCoordinator` creates a `CountDownLatch(3)` to synchronize three
initialization steps. The `initialize(boolean quickMode)` method only calls
`countDown()` when `quickMode` is `false`. When quick mode is active, the latch
never reaches zero and `waitForStartup()` blocks forever.

A plain `@Test` can check the latch count and move on. What it does not do is wait, and waiting
is where the missing `countDown()` stops being a number and becomes a startup that never
completes. Under `@AsyncTest` every thread takes the quick path and every thread's wait gives up,
which is what `CountDownLatchDetector` reports.

## How to Reproduce

1. Open `StartupCoordinatorTest.java`.
2. Remove the `@Disabled` annotation from `testInitialize_concurrent_detectsMissingCountDown`.
3. Run the test:

```
COUNTDOWNLATCH ISSUES DETECTED:
  Timed Out Latches:
    - startup-latch (expected 3 countDown() calls, but await() timed out)
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## What the detector reports, and what it does not

A registered latch that has not reached zero is **not** a finding on its own, and should not be:
a latch mid-flight looks exactly like that, so reporting it would flag every latch the moment it
is created. `hasIssues()` gates on a wait that gave up, or on more `countDown()` calls than the
latch was built for.

That is why this demonstration has to wait. Before issue #346 it registered the latch, called
`initialize(true)` and stopped, with a comment claiming the detector would notice the missing
`countDown()`. It does not, and enabling the demonstration produced no report at all.
`StartupCoordinator.observeLatch` now reports the countDown, the successful wait and the
timed-out wait from inside the coordinator, and the demonstration waits 20ms for a startup that
is never going to happen.

## The Fix

Always call `latch.countDown()` regardless of `quickMode`, or use a separate
latch per code path so every party that registers also decrements.
