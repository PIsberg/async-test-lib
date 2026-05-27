# Example 45 — CyclicBarrier Broken by Exception

Demonstrates **CyclicBarrierDetector** catching a barrier that is broken when
one participant throws during a phase, rendering the barrier permanently unusable.

## The Problem

`BatchProcessor` coordinates four worker threads with a `CyclicBarrier(4)`.
During phase 2, one thread throws a `RuntimeException` before reaching
`barrier.await()`. This breaks the barrier: all other threads waiting at the
barrier receive `BrokenBarrierException` on their current or next call.

A plain `@Test` does not exercise concurrent phase execution, so the exception
path and its barrier-breaking side-effect are never triggered.

## How to Reproduce

1. Open `BatchProcessorTest.java`.
2. Remove the `@Disabled` annotation from `testProcessPhase_concurrent_detectsBrokenBarrier`.
3. Run the test — `CyclicBarrierDetector` will report a broken barrier.

## The Fix

Reset the barrier via `barrier.reset()` after handling the exception, or use a
`Phaser` which tolerates deregistration of individual parties.
