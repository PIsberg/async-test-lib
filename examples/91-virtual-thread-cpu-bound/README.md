# Example 91 — Virtual Thread CPU-Bound Task

**Detector**: `VirtualThreadCpuBoundTaskDetector`  
**Flag**: `detectVirtualThreadCpuBoundTasks = true`

## The Problem

`CryptoService.encrypt()` runs a CPU-intensive computation loop. The caller
launches it on virtual threads (via `Thread.ofVirtual().start()`). Virtual
threads are designed for I/O-bound work: they park their carrier during blocking
operations so the carrier can serve other virtual threads. A CPU-bound loop
never blocks, so the virtual thread occupies its carrier the entire time — no
better than a platform thread but with additional scheduling overhead.

Under concurrency:
- All carrier threads (= CPU cores) are pinned by CPU-burning virtual threads.
- No new virtual threads can be scheduled until a carrier is freed.
- The virtual-thread model provides no throughput benefit for CPU-bound work.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsCpuBoundTask`
and run the test. `VirtualThreadCpuBoundTaskDetector` measures task duration
and reports tasks that run longer than the configured CPU-bound threshold
without ever yielding.

## The Fix

Run CPU-bound tasks on a dedicated platform-thread pool
(`Executors.newFixedThreadPool(nCpus)`). Reserve virtual threads for I/O-bound
or high-concurrency / low-CPU workloads.
