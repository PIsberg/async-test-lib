# Example 53 — Stale Context via InheritableThreadLocal

Demonstrates **InheritableThreadLocalMisuseDetector** catching stale request
context propagated through a thread pool via `InheritableThreadLocal`.

## The Problem

`RequestContextHolder` uses `InheritableThreadLocal<String>` to propagate a
request ID from a parent thread to child threads. With a fixed thread pool,
worker threads are reused across requests. A thread created during request A
inherits request A's context; when that thread is later reused for request B it
still carries the stale request-A context unless it is explicitly cleared.

A plain `@Test` creates a fresh thread each time so no stale inheritance occurs.
Concurrent invocations on pooled threads reveal the contamination.

## How to Reproduce

1. Open `RequestContextHolderTest.java`.
2. Remove the `@Disabled` annotation from `testGetRequestId_concurrent_detectsStaleContext`.
3. Run the test — `InheritableThreadLocalMisuseDetector` will report the
   InheritableThreadLocal accessed on a pool thread that inherited stale context.

## The Fix

Use plain `ThreadLocal` and explicitly copy the value into each task via a
wrapper, or adopt a structured-concurrency approach with `ScopedValue` (Java 21+)
which does not propagate through thread-pool reuse.
