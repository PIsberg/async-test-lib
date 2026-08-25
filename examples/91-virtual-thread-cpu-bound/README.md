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

## An iteration count is not a duration

`VirtualThreadCpuBoundTaskDetector` compares elapsed wall time since the last recorded yield
point against a 50ms threshold. Whether a task crosses that threshold is a question about
**time**, and until issue #346 this example answered it with an iteration count: 500,000 rounds
of integer arithmetic, which the JIT finishes in well under a millisecond.

So the demonstration fired only when the machine happened to be loaded enough to stretch that
work past 50ms of wall clock, which is why it appears in the issue's "fires in one or two runs
out of three" list rather than the "never fires" one. A demonstration that depends on the
reader's machine being busy is not a demonstration.

`CryptoService.CPU_WORK_MILLIS` is now a duration, and `burnCpu` spins until the clock says it is
done, writing into a `volatile` field so the JIT cannot delete the loop.
`test_encryptOccupiesItsThreadForTheStatedTime` pins that it really takes that long.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsCpuBoundTask` and run the test:

```
🟡 MEDIUM: CPU-bound tasks detected on virtual threads
  Tasks recorded=8, avgDuration=134ms, maxDuration=135ms, threshold=50ms
    - Virtual thread (id=56) task 'crypto-encrypt': ran 134ms without a yield point
      (threshold=50ms, total yields=0). Consider using platform threads for CPU-bound work.
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## The Fix

Run CPU-bound tasks on a dedicated platform-thread pool
(`Executors.newFixedThreadPool(nCpus)`). Reserve virtual threads for I/O-bound
or high-concurrency / low-CPU workloads.
