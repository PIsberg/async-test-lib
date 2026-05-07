# MDC Context Leak Example

This example demonstrates the **MdcContextLeakDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`RequestHandler.handle()` sets MDC keys (`requestId`, `userId`) for structured logging but
never clears them. When the thread is returned to a pool and picks up the next request, the
stale entries are still present. Request B's log lines show request A's `requestId` — a
**silent data leak** that is almost impossible to diagnose from log output alone.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_requestHandled_singleThread() {
    RequestHandler handler = new RequestHandler();
    assertEquals("processed:req-001", handler.handle("req-001", "user-42"));
    // ✅ Passes — only checks the return value, not the MDC state left behind
}
```

The test verifies the result but ignores what the thread's MDC looks like afterward.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectMdcContextLeak = true, timeoutMs = 5000)
void part2_detectMdcLeak() {
    var d = AsyncTestContext.mdcContextLeakDetector();
    Map<String,String> before = new HashMap<>(MDC_STORE.get());
    d.recordTaskStart(Thread.currentThread(), before);
    try {
        handler.handle("req-001", "user-42");
    } finally {
        d.recordTaskEnd(Thread.currentThread(), new HashMap<>(MDC_STORE.get()));
        // BUG: no MDC.clear() here
    }
}
```

The detector reports:

```
MDC CONTEXT LEAK DETECTED:
  - Thread 'Thread-2' left 2 MDC key(s) behind: [requestId, userId]
    These entries will contaminate the next task on this pooled thread.
    Fix: use MDC.clear() or remove individual keys in a finally block.
```

## Running the Example

```bash
cd examples/12-mdc-context-leak
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
String handleFixed(String requestId, String userId) {
    MDC_STORE.get().put("requestId", requestId);
    MDC_STORE.get().put("userId", userId);
    try {
        return "processed:" + requestId;
    } finally {
        MDC_STORE.get().remove("requestId"); // ✅ Clean up
        MDC_STORE.get().remove("userId");    // ✅ Clean up
    }
}
```

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Stale MDC entries | Logs from request B show request A's user/trace IDs — misleading audit trails |
| Security implication | User identifiers from one request visible in another request's log context |
