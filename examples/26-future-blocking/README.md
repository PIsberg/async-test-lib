# Example 26 — Future Blocking / Thread-Pool Starvation

## The Problem

`BatchProcessingService.processBatch()` submits N work items to a
`newFixedThreadPool(4)` and then calls `Future.get()` for each one —
all from **within a thread that itself belongs to the same pool**.

When 4 concurrent callers each invoke `processBatch()`, all 4 pool threads
are blocked on `get()`. The subtasks they submitted are queued behind the
blocked threads and can never start. The pool is starved:

```
Worker-0: blocking on item[0].get()  ─┐
Worker-1: blocking on item[1].get()  ─┤  queue: [item subtasks...]
Worker-2: blocking on item[2].get()  ─┤  no free thread to drain it
Worker-3: blocking on item[3].get()  ─┘
```

## Why This Happens

`Future.get()` parks the calling thread until the future's task completes.
If the task was submitted to the same bounded pool whose thread is now parked,
the pool loses that thread as a resource. Repeat for every pool thread and you
have a **starvation deadlock**: every thread is waiting, nothing can run.

The bug is invisible in a simple unit test because the test thread is *outside*
the pool, so the 4 pool threads are idle when `get()` is called and the
subtasks complete immediately.

## How to Reproduce

1. Open `BatchProcessingServiceTest`.
2. Remove `@Disabled` from `testProcessBatch_concurrent_detectsFutureBlockingStarvation`.
3. Run the test.

`FutureBlockingDetector` will report:

```
FUTURE BLOCKING ISSUES DETECTED:
  - batch-worker-pool: 4/4 workers blocked waiting on futures while 4 task(s) remain queued
  Fix:
    - Submit blocking-wait tasks to a different (unbounded or larger) executor
    - Use non-blocking composition instead: thenApply/thenCompose instead of get()/join()
```

## The Solution

Route the per-item subtasks through a **separate** thread pool so the
orchestrating threads can safely block without consuming the only available
capacity:

```java
// Before (starvation risk)
Future<String> f = workerPool.submit(() -> processItem(item));
results.add(f.get()); // blocks a workerPool thread

// After (safe)
Future<String> f = subtaskPool.submit(() -> processItem(item));
results.add(f.get()); // subtaskPool has free threads — no starvation
```

The cleanest solution eliminates blocking entirely:

```java
CompletableFuture.allOf(
    items.stream()
         .map(item -> CompletableFuture.supplyAsync(() -> processItem(item), subtaskPool))
         .toArray(CompletableFuture[]::new)
).join();
```

## Key Takeaways

- Calling `Future.get()` or `CompletableFuture.join()` from a thread that
  belongs to a bounded pool consumes that thread as a resource until the
  future completes.
- If the future's task was submitted to the **same** pool, you have a hidden
  starvation risk that only manifests under concurrent load.
- Use separate pools for orchestration vs. execution, or redesign with
  non-blocking async composition.
- Virtual threads sidestep this entirely: blocking a virtual thread is
  carrier-thread-safe and does not exhaust the pool.
