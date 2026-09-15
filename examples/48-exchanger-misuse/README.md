# Example 48 — Exchanger Without a Guaranteed Partner

Demonstrates **ExchangerDetector** catching an `Exchanger` used in a context
where the number of callers is not always even, leaving a thread waiting
indefinitely for a partner that never arrives.

## The Problem

`DataSyncService.exchangeData()` uses an `Exchanger<String>` to let pairs of threads swap
payloads, and waits without a bound. Under concurrent load with an odd number of active threads,
one thread calls `exchange()` with no partner available and never returns.

A plain `@Test` never has two callers at once, so it either uses the bounded
`exchangeDataWithin()` and sees a timeout, or cannot call `exchangeData()` at all.

## What is not the problem

A timed exchange that times out and handles it has left the exchanger, and the detector does not
report it (#585). Earlier versions of this example treated that timeout as the bug; it is the fix.

## The thread count is part of the bug

This demonstration runs on **7** threads, and the odd number is the whole point. An `Exchanger`
pairs its callers: an even number of them all find a partner, nobody is left waiting, and there is
nothing to report. Before issue #346 this example ran on 8 threads and produced an empty report
three runs out of three, not because the detector was wrong but because the condition it looks
for could not arise.

If you change the thread count here, change it to another odd number.

## How to Reproduce

1. Open `DataSyncServiceTest.java`.
2. Remove the `@Disabled` annotation from `testExchangeData_concurrent_detectsOrphanedCaller`.
3. Run the test. The odd caller holds the round until `timeoutMs` (2 s); the runner interrupts it,
   and the report printed with the timeout says:

```
EXCHANGER ISSUES DETECTED:
  CRITICAL: Orphaned Exchanges:
    - data-sync-exchanger: 1 of 7 started exchange(s) never ended (completed: 6, timed out: 0, interrupted: 0)
```

The timeout message names `ExchangerDetector` as the detector that had a finding.

## The Fix

Call `exchangeDataWithin()` so a caller left without a partner gives up, or ensure the exchanger
is always called by an even number of threads simultaneously, or replace it with a
`SynchronousQueue` or explicit pairing via a queue-based producer-consumer design.
