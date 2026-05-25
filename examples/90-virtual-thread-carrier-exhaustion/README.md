# Example 90 — Virtual Thread Carrier Exhaustion

**Detector**: `VirtualThreadCarrierExhaustionDetector`  
**Flag**: `detectVirtualThreadCarrierExhaustion = true`

## The Problem

`VirtualWorkerService.processRequest()` acquires a `synchronized` lock and
calls `Thread.sleep(10)` inside it (simulating slow I/O). When a virtual thread
enters a `synchronized` block, the JVM **pins** it to its carrier thread — the
virtual thread cannot unmount during the sleep. With many virtual threads all
pinned at once, the carrier thread pool (sized to the number of CPU cores) is
exhausted. No more virtual threads can be scheduled until a carrier is freed.

Under concurrency:
- Every concurrent invocation pins one carrier thread for at least 10 ms.
- Once all carriers are pinned, new virtual threads queue up waiting.
- Throughput collapses to carrier-count / 10 ms regardless of virtual thread count.

## How to Reproduce

Remove the `@Disabled` annotation from
`test_concurrent_detectsCarrierExhaustion` and run the test.
`VirtualThreadCarrierExhaustionDetector` counts concurrent blocking events and
reports when the carrier pool is exhausted.

## The Fix

Replace `synchronized` with `ReentrantLock`. Virtual threads can unmount from
their carrier while waiting on a `ReentrantLock`, so carriers are freed during
the blocking operation.
