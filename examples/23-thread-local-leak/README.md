# Thread-Local Leak Example

This example demonstrates a **real-world security and correctness bug** found in many web applications: **a ThreadLocal that stores authentication context is never cleared, leaking the previous request's user identity to the next request on a reused thread-pool thread**.

## The Problem

The `RequestContextService` stores the authenticated user ID in a `ThreadLocal<String>` so that any code on the same thread can access it without explicit parameter passing. `beginRequest()` sets the value; `getRequestUserId()` reads it.

**The Bug**: `endRequest()` (which calls `ThreadLocal.remove()`) is never called after each request completes. In a thread pool:

1. Request 1 arrives on Thread A — `beginRequest("alice")` sets the ThreadLocal
2. Request 1 finishes — thread returns to the pool *without* calling `endRequest()`
3. Request 2 is dispatched to the same Thread A
4. Request 2 (for user "bob") skips `beginRequest()` or calls `getRequestUserId()` first
5. **Request 2 reads "alice"** — a completely different user's authentication data

## Why It Happens

```java
// BUGGY CODE (RequestContextService.java):
public void beginRequest(String userId) {
    CURRENT_USER.set(userId);   // ❌ no matching remove() guaranteed
}

// Missing enforcement:
public void endRequest() {
    CURRENT_USER.remove();      // exists but never called in the buggy flow
}
```

The cleanup method exists, but nothing enforces that it is called. Production code rarely has bugs in the happy path — the leak manifests when an exception short-circuits the normal cleanup, or when callers simply forget.

## How to Reproduce

### 1. Run with @Test (PASSES - false confidence)

```bash
cd examples/23-thread-local-leak
mvn clean test
# Tests pass: single-threaded tests always set before reading
```

Single-threaded execution always runs `beginRequest()` before `getRequestUserId()`, so the correct user is returned. The stale-data scenario is invisible.

### 2. Run with @AsyncTest (DETECTS the ThreadLocal leak)

Remove the `@Disabled` annotation from `testBeginRequest_concurrent_detectsThreadLocalLeak()`:

```java
@AsyncTest(threads = 8, invocations = 20, detectAll = false, detectThreadLocalLeaks = true)
void testBeginRequest_concurrent_detectsThreadLocalLeak() { ... }
```

```bash
mvn clean test
# ThreadLocalMonitor reports:
#   "REQUEST_USER: accessed by 8 thread(s) without remove()"
#   "REQUEST_USER: value crossed N reused thread(s)"
```

With 8 threads each calling `beginRequest()` but never `endRequest()`:
- `ThreadLocalMonitor.recordThreadLocalInit()` tracks each initialization
- `recordThreadLocalAccess()` tracks which threads used the ThreadLocal
- `recordThreadLocalCleanup()` is never called
- `analyzeThreadLocalLeaks()` flags "REQUEST_USER" as missing cleanup and reports cross-thread contamination

## The Root Cause

`ThreadLocal` values survive thread reuse in a pool. When a thread finishes a request and is returned to the pool, its `ThreadLocal` map is not automatically cleared. The next task submitted to that thread inherits all remaining ThreadLocal values from the previous task. Under concurrent stress:

1. 8 threads each have "REQUEST_USER" set to different user IDs
2. `ThreadLocalMonitor` sees the ThreadLocal initialized across all 8 threads
3. No thread ever calls `recordThreadLocalCleanup()`
4. The monitor flags the leak and warns about cross-thread contamination

## The Solution

Wrap the request handling in a `try-finally` block so cleanup is guaranteed:

```java
// FIXED CODE — always clear in finally:
public void handleRequest(String userId) {
    try {
        service.beginRequest(userId);
        // ... handle the request ...
        String result = processRequest();
        // ... return result ...
    } finally {
        service.endRequest();  // ✅ guaranteed even if exception is thrown
    }
}
```

Or use a `ScopedValue` (Java 21+) which is automatically unbound when the scope exits:

```java
// MODERN ALTERNATIVE — ScopedValue (Java 21+):
static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();

public void handleRequest(String userId) {
    ScopedValue.runWhere(CURRENT_USER, userId, () -> {
        // CURRENT_USER is bound only within this lambda — no cleanup needed
        processRequest();
    });
}
```

## Files in This Example

- **`RequestContextService.java`** — Buggy service with un-cleaned ThreadLocal
- **`RequestContextServiceTest.java`** — Tests that demonstrate the problem
  - `testBeginRequest_singleThread_returnsCorrectUser()` — Passes with @Test
  - `testBeginRequest_concurrent_detectsThreadLocalLeak()` — Detects leak with @AsyncTest
  - `testBeginRequest_fixedWithFinally_singleThread()` — Shows the correct pattern
- **`pom.xml`** — Maven dependencies (JUnit 5 + async-test-lib)

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests always set before they read
2. **@AsyncTest finds the leak**: 8 threads × 20 invocations accumulates leaked ThreadLocals across reused threads
3. **Thread pool threads live forever**: Unlike request threads, pool threads survive between tasks and carry stale ThreadLocal data
4. **Always pair set() with remove()**: Use `try-finally` to guarantee cleanup even in exception paths
5. **Prefer ScopedValue (Java 21+)**: Automatically unbound at scope exit — no manual cleanup required
