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

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsContamination`
and run the test. `ThreadLocalContaminationDetector` reports threads whose
`ThreadLocal` values were set but never removed.

## The Fix

Call `REQUEST_ID.remove()` in a `finally` block inside `endRequest()` — or
use try-with-resources on a scope object that cleans up on close.
