# Example 79 — Structured Concurrency Misuse

**Detector**: `StructuredConcurrencyMisuseDetector`  
**Flag**: `detectStructuredConcurrencyIssues = true`

## The Problem

`DataFetchService.fetchAll()` creates a `StructuredTaskScope.ShutdownOnFailure`,
forks subtasks for each ID, calls `scope.join()` — but never calls
`scope.close()`. `StructuredTaskScope` implements `AutoCloseable` precisely
because it must release its internal thread resources on close.

Without `close()`:
- Forked virtual threads may remain running or in a zombie state.
- The scope's internal bookkeeping structures are never freed.
- Under concurrent load, each call leaks a scope and its threads — the JVM's
  virtual-thread carrier pool can eventually be exhausted.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsUnclosedScope`
and run the test. `StructuredConcurrencyMisuseDetector` records every
`recordScopeOpened`, `recordSubtaskForked`, `recordJoinCalled`, and
`recordScopeClosed` event, then flags scopes that were opened but never closed.

## The Fix

Always open a `StructuredTaskScope` inside try-with-resources:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    ids.forEach(id -> scope.fork(() -> fetch(id)));
    scope.join().throwIfFailed();
    return scope.resultNow();
}
```
