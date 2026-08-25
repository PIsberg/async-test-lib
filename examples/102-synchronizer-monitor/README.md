# Example 102 — Synchronizer Monitor

Demonstrates **SynchronizerMonitor** detecting over-synchronization and barrier issues.

## The Problem

`CoordinationService.execute()` acquires three independent synchronization primitives
in sequence — a `Semaphore`, a `ReentrantLock`, and a `CountDownLatch` — for every
single task execution. This creates unnecessary contention, makes the locking protocol
hard to reason about, and introduces risk of partial completion (e.g. latch counted down
but lock not released on an exception path).

The latch is the part `SynchronizerMonitor` can see, and it is worth looking at closely. It is
meant to be a start gate for `expectedParties` workers. It is built with a count of **1**, so it
opens on the first arrival, and then it is **replaced**:

```java
CountDownLatch gate = startGate;
...
gate.countDown();
...
startGate = new CountDownLatch(1);   // the gate the other workers hold is discarded
```

A worker that captured the old reference is now waiting on an object nobody will count down, and
the replacement has never heard of anybody who already arrived. No gate ever gathers more than one
party.

## What the monitor reports

`SynchronizerMonitor` counts arrivals per synchronizer instance and reports one that fewer parties
reached than it was registered for. Registering each gate for `expectedParties` and recording the
arrival that actually happens gives it exactly that.

That registration is what this example used to get wrong. It registered the **Semaphore** with
`expectedParties = 1` and recorded one arrival per body execution: one arrival out of one expected
is a barrier working, so the report was empty three runs out of three (issue #346).

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsOverSynchronization` in
   `CoordinationServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. It fails:

```
SYNCHRONIZER ISSUES DETECTED:

Incomplete barrier advances:
  - CountDownLatch: 1/8 parties arrived
  Fix: Ensure all parties reach barrier before advancing
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

You do not need the detector to see the replacement: `testExecute_replacesTheGateEveryTime` runs on
every build and shows that the gate a worker arrives at is not the gate the next worker will find.

## The Fix

Use a single `ReentrantLock` for mutual exclusion. Replace the `CountDownLatch` with
a simpler condition variable if signalling is needed. Semaphores are only needed when
limiting concurrency to N > 1.
