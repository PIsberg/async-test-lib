# Example 92 — Virtual Thread Pinning

**Detector**: `VirtualThreadPinningDetector`  
**Flag**: `detectVirtualThreadPinning = true`

## The Problem

`LegacyService.fetchData()` is declared `synchronized` and calls
`Thread.sleep(10)` (simulating a blocking I/O call) while holding the monitor.
When a virtual thread executes a `synchronized` method it is pinned to its
carrier thread: the JVM cannot unmount the virtual thread to let the carrier
serve other work. The carrier is tied up for the full blocking duration,
defeating the scalability benefit of virtual threads.

Under concurrency:
- Each concurrent invocation pins one carrier thread for at least 10 ms.
- Carrier exhaustion occurs once all CPU cores are occupied by pinned threads.
- Effective throughput drops to cores / 10 ms, far below virtual-thread potential.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsPinning` and run
the test. `VirtualThreadPinningDetector` records pinning events via
`recordPinningEvent` / `recordUnpinEvent` and reports threads that remained
pinned during blocking operations.

## The Fix

Replace the `synchronized` method with an explicit `ReentrantLock`. Virtual
threads can unmount from their carrier while parked on a `ReentrantLock`,
allowing the carrier to execute other virtual threads during the wait.
