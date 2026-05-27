# Example 47 — Double-Checked Locking Without volatile

Demonstrates **DoubleCheckedLockingDetector** catching a classic DCL singleton
whose instance field is not declared `volatile`, allowing partially constructed
objects to be observed by other threads.

## The Problem

`ConfigManager.getInstance()` uses the double-checked locking pattern but the
`instance` field is not `volatile`. The JVM is permitted to reorder the write
to `instance` before the constructor body completes. A second thread passing the
first null-check can therefore see a non-null but incompletely initialized object.

A plain `@Test` calls `getInstance()` from a single thread where reordering has
no observable effect. Concurrent access exposes the race.

## How to Reproduce

1. Open `ConfigManagerTest.java`.
2. Remove the `@Disabled` annotation from `testGetInstance_concurrent_detectsBrokenDCL`.
3. Run the test — `DoubleCheckedLockingDetector` will report the unsafe field.

## The Fix

Declare the field `private static volatile ConfigManager instance`, or use the
initialization-on-demand holder idiom which relies on class-loading guarantees
instead of explicit locking.
