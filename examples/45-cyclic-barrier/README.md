# Example 45 — CyclicBarrier Broken by Exception

Demonstrates **CyclicBarrierDetector** catching workers that arrive at a barrier which
is already broken, after one participant's failure broke it and nobody reset it.

## The Problem

`BatchProcessor` coordinates four worker threads with a `CyclicBarrier(4)`.
During phase 2, one worker in four throws a `RuntimeException` before reaching the
barrier. That does not break the barrier by itself: it strands the workers already
waiting for a fourth party. They wait with a timeout, and a timed-out `await` does
break the barrier. Nothing calls `reset()`, so from then on every `await` throws
`BrokenBarrierException` at once and the processor never coordinates again.

A plain `@Test` does not exercise concurrent phase execution, so no worker is ever
stranded and the barrier never breaks.

## How to Reproduce

1. Open `BatchProcessorTest.java`.
2. Remove the `@Disabled` annotation from `testProcessPhase_concurrent_detectsBrokenBarrier`.
3. Run the test. Each worker runs phase 2 and then phase 3. The first round strands two
   workers and breaks the barrier; `CyclicBarrierDetector` sees workers come back for
   phase 3 to a barrier they already saw broken (their own timeout, or a
   `BrokenBarrierException`) with no `reset()` in between, and reports reuse of a broken
   barrier.

The detector asks the barrier rather than trusting a recorded break: breaking a
barrier on purpose to cancel its parties, and then discarding it, is correct code and
is not reported, and neither is one arrival that hits the break.

## The Fix

Reset the barrier via `barrier.reset()` (or replace it) once the failed phase has been
handled, or use a `Phaser`, which tolerates a party deregistering.
