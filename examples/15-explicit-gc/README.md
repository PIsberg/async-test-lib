# Explicit GC Example

This example demonstrates the **ExplicitGcDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`CacheManager.evictAll()` calls `System.gc()` after clearing the cache. In production
code this is a hint to the JVM that can trigger a full stop-the-world collection, causing
unpredictable latency spikes. During concurrent stress tests it inflates operation times
and can cause artificial timeouts that mask real concurrency bugs.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_evictCache_singleThread() {
    cache.put("user:1", "Alice");
    cache.evictAll(); // calls System.gc() — @Test has no latency assertion
    assertNull(cache.get("user:1")); // ✅ Passes
}
```

No latency or timing assertions exist, and a single GC hint has negligible effect in a
sequential test. The `System.gc()` is invisible to the assertion.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectExplicitGc = true, timeoutMs = 5000)
void part2_detectExplicitGc() {
    var d = AsyncTestContext.explicitGcDetector();
    d.recordGcInvocation(Thread.currentThread(), "CacheManager.evictAll");
    cache.evictAll(); // triggers detection
}
```

The detector reports:

```
EXPLICIT GC DETECTED:
  - System.gc() requested by 'Thread-2' at [CacheManager.evictAll]
    Explicit GC causes unpredictable STW pauses during concurrent tests.
    Fix: remove System.gc(); let the JVM decide when to collect.
```

## Running the Example

```bash
cd examples/15-explicit-gc
mvn clean test
# ✅ Tests pass — no visible effect in sequential execution

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
void evictAllFixed() {
    cache.clear(); // ✅ Clear the data; don't hint GC
}
```

If reclamation timing genuinely matters, use `WeakReference` or a proper eviction policy
(e.g., Caffeine, Guava Cache) rather than `System.gc()`.

## Severity

| Failure mode | Symptom |
|-------------|---------|
| STW pause during stress test | Latency spikes produce artificial timeouts that mask real bugs |
| Production risk | `System.gc()` in production code can stall GC-sensitive workloads for seconds |
