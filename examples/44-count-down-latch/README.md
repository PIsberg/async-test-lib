# Example 44 — CountDownLatch Misuse

Demonstrates **CountDownLatchDetector** catching a latch that never reaches zero
because one code path skips the `countDown()` call.

## The Problem

`StartupCoordinator` creates a `CountDownLatch(3)` to synchronize three
initialization steps. The `initialize(boolean quickMode)` method only calls
`countDown()` when `quickMode` is `false`. When quick mode is active, the latch
never reaches zero and `waitForStartup()` blocks forever.

A plain `@Test` never reveals this because it always calls the method with the
same argument. Under concurrent load with mixed arguments the missing count-down
is detected immediately by `CountDownLatchDetector`.

## How to Reproduce

1. Open `StartupCoordinatorTest.java`.
2. Remove the `@Disabled` annotation from `testInitialize_concurrent_detectsMissingCountDown`.
3. Run the test — `CountDownLatchDetector` will report a latch whose count never
   reached zero.

## The Fix

Always call `latch.countDown()` regardless of `quickMode`, or use a separate
latch per code path so every party that registers also decrements.
