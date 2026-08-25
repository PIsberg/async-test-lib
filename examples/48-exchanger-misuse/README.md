# Example 48 — Exchanger Without a Guaranteed Partner

Demonstrates **ExchangerDetector** catching an `Exchanger` used in a context
where the number of callers is not always even, causing some threads to wait
indefinitely for a partner that never arrives.

## The Problem

`DataSyncService` uses an `Exchanger<String>` to let pairs of threads swap
payloads. Under concurrent load with an odd number of active threads, one thread
calls `exchange()` with no partner available. Even with a timeout, repeated
timeouts indicate a structural mismatch in how the exchanger is used.

A plain `@Test` always provides exactly one caller at a time so no blocking
occurs. Concurrent execution with an uneven thread count exposes the imbalance.

## The thread count is part of the bug

This demonstration runs on **7** threads, and the odd number is the whole point. An `Exchanger`
pairs its callers: an even number of them all find a partner, nothing times out, and there is
nothing to report. Before issue #346 this example ran on 8 threads and produced an empty report
three runs out of three, not because the detector was wrong but because the condition it looks
for could not arise.

If you change the thread count here, change it to another odd number.

## How to Reproduce

1. Open `DataSyncServiceTest.java`.
2. Remove the `@Disabled` annotation from `testExchangeData_concurrent_detectsTimeout`.
3. Run the test:

```
EXCHANGER ISSUES DETECTED:
  Timed Out Exchanges:
    - data-sync-exchanger (exchange timed out - no partner thread found)
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## The Fix

Ensure the exchanger is always called by an even number of threads simultaneously,
or replace it with a `SynchronousQueue` or explicit pairing via a queue-based
producer-consumer design.
