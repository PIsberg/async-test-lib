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

## How to Reproduce

1. Open `DataSyncServiceTest.java`.
2. Remove the `@Disabled` annotation from `testExchangeData_concurrent_detectsTimeout`.
3. Run the test — `ExchangerDetector` will report timeout events on the exchanger.

## The Fix

Ensure the exchanger is always called by an even number of threads simultaneously,
or replace it with a `SynchronousQueue` or explicit pairing via a queue-based
producer-consumer design.
