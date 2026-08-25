# Example 85 — Thread Local Contamination

**Detector**: `ThreadLocalContaminationDetector`  
**Flag**: `detectThreadLocalContamination = true`

## The Problem

`RequestScopedService.startRequest()` stores the current request ID in a static
`ThreadLocal<String>`. `endRequest()` should call `REQUEST_ID.remove()` to
clean up, but it does not. When a thread-pool thread is reused for a subsequent
request, `getCurrentId()` returns the previous request's ID — silently
associating actions with the wrong request.

Under concurrency:
- Auditing, tracing, and security checks read a stale request ID.
- Bugs are non-deterministic: they appear only when a thread is reused, and
  the wrong ID depends on which thread happened to handle the previous request.

## What the detector looks for

Not "a value that was never removed". Specifically: **a task reading a value that an earlier task
on the same thread set**. That is what contamination is, and it is why two things about the
demonstration are load-bearing.

**The read has to come before the write.** A task that calls `startRequest()` and then reads is
reading its own value, in its own task, however many times the thread is reused. This example used
to do exactly that, and reported nothing three runs out of three (issue #346). The demonstration
now reads the context *first*, the way a downstream logging or auditing component does: on a fresh
thread that is null, and on a reused one it is the previous request's id.

**`useVirtualThreads = false`.** The whole subject is a pool reusing a thread for the next task,
and the default runner does not reuse threads at all: every body execution gets a fresh virtual
thread, so the task count on each of them is always 1 and nothing can be inherited. On platform
threads the runner really does reuse eight of them across the rounds. See issue #352.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsContamination` and run the test:

```
THREADLOCAL CONTAMINATION DETECTED:
  - Thread 'async-test-worker-6' in 'process-62': read REQUEST_ID whose value was set in
    task 1 — not cleared between tasks
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## The Fix

Call `REQUEST_ID.remove()` in a `finally` block inside `endRequest()` — or
use try-with-resources on a scope object that cleans up on close.
