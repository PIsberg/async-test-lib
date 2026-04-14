# Async Test Library - Examples

Real-world examples demonstrating common Java concurrency bugs that `@AsyncTest` finds but standard `@Test` misses.

## Available Examples

| # | Example | Primary Detector | Async Problem | Severity |
|---|---------|------------------|---------------|----------|
| 01 | [CompletableFuture Exception Handling](01-completablefuture-exception-handling/) | `CompletableFutureExceptionDetector` | Unhandled exceptions in async chains cause silent data loss | 🔴 Critical |
| 02 | [Visibility/Volatile Flag](02-visibility-volatile-flag/) | `VisibilityMonitor` | Missing `volatile` on shared flags causes threads to never see shutdown signals | 🔴 Critical |
| 03 | [Shared Non-Thread-Safe Collection](03-shared-collection/) | `SharedCollectionDetector` | ArrayList/HashMap shared across threads causes data loss and corruption | 🔴 Critical |
| 04 | [Virtual Thread Context Leak](04-virtual-thread-context-leak/) | `VirtualThreadContextLeakDetector` | ThreadLocal leaks in virtual threads cause memory leaks | 🟡 High |

## Phase 7: High-Level Concurrency Patterns (New!)

The library now includes 4 new important detectors for common concurrency patterns:

### 1. HttpClientConcurrencyDetector
**What**: Detects unclosed HTTP responses, connection pool exhaustion, and incomplete async HTTP operations.

**Impact**: Resource leaks, connection pool starvation, silent request failures.

**Usage**:
```java
@AsyncTest(threads = 10, detectHttpClientIssues = true)
void testHttpClient() {
    AsyncTestContext.httpClientDetector()
        .recordClientCreated(client, "api-client");
    AsyncTestContext.httpClientDetector()
        .recordRequestSent(request, "api-call");
    AsyncTestContext.httpClientDetector()
        .recordResponseReceived(response, "api-call");
}
```

### 2. StreamClosingDetector
**What**: Detects InputStream/OutputStream/Reader/Writer instances not properly closed in concurrent code.

**Impact**: File descriptor leaks, resource exhaustion, locked files.

**Usage**:
```java
@AsyncTest(threads = 10, detectStreamClosing = true)
void testStreams() throws IOException {
    InputStream is = new FileInputStream("data.txt");
    AsyncTestContext.streamClosingDetector()
        .recordStreamOpened(is, "data-input");
    try {
        // use stream
    } finally {
        is.close();
        AsyncTestContext.streamClosingDetector()
            .recordStreamClosed(is, "data-input");
    }
}
```

### 3. CacheConcurrencyDetector
**What**: Detects HashMap/LinkedHashMap used as cache without synchronization, concurrent read/write issues.

**Impact**: Data corruption, ConcurrentModificationException, cache stampede.

**Usage**:
```java
@AsyncTest(threads = 10, detectCacheConcurrency = true)
void testCache() {
    Map<String, Object> cache = new HashMap<>();
    AsyncTestContext.cacheConcurrencyDetector()
        .registerCache(cache, "user-cache");
    AsyncTestContext.cacheConcurrencyDetector()
        .recordPut(cache, "user-cache", "key", value);
    AsyncTestContext.cacheConcurrencyDetector()
        .recordGet(cache, "user-cache", "key");
}
```

### 4. CompletableFutureChainDetector
**What**: Detects missing exception handlers, unjoined futures, and improper CompletableFuture chain usage.

**Impact**: Swallowed exceptions, resource leaks, incomplete async operations.

**Usage**:
```java
@AsyncTest(threads = 10, detectCompletableFutureChainIssues = true)
void testCFChain() {
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "result");
    AsyncTestContext.cfChainDetector()
        .recordFutureCreated(future, "async-operation");
    
    CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());
    AsyncTestContext.cfChainDetector()
        .recordChainOperation(future, chained, "thenApply");
    AsyncTestContext.cfChainDetector()
        .recordExceptionally(future);
    
    String result = chained.join();
    AsyncTestContext.cfChainDetector()
        .recordFutureJoined(chained, "async-operation");
}
```

## Quick Start

Each example is a standalone Maven project that:
- ✅ **Passes** with `@Test` (sequential execution - false confidence)
- ❌ **Fails** with `@AsyncTest` (concurrent stress - exposes the real bug)
- 📖 Includes detailed comments explaining the problem and solution

### Running in IntelliJ

**⚠️ Important**: If you get `NoSuchMethodError: methodParameterTypes` when running tests directly in IntelliJ:

This is because **IntelliJ's bundled JUnit runner is older** than JUnit 6.0.3 used by the examples.

**Solution - Run tests via Maven:**
1. Right-click the test class → `Run 'OrderProcessingServiceTest' via Maven`
2. Or use the Maven tool window → example module → `test` lifecycle
3. Or run from terminal: `mvn clean test`

**Alternative**: Update IntelliJ to the latest version which supports JUnit 6.x

### Running from Command Line

```bash
# Run all examples (they pass with @Test)
for dir in examples/*/; do
  mvn -f "$dir/pom.xml" clean test
done

# To see the bugs, change @Test to @AsyncTest in any example test
```

## Example Structure

```
examples/
├── README.md                                    # This file
├── example-01-completablefuture-exception-handling/
│   ├── README.md                                # Detailed explanation of the bug
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../OrderProcessingService.java      # Buggy production code
│       └── test/java/.../OrderProcessingServiceTest.java  # Tests + solution
├── example-02-visibility-volatile-flag/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../TaskProcessorService.java        # Buggy production code
│       └── test/java/.../TaskProcessorServiceTest.java    # Tests + solution
├── 03-shared-collection/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../EventAggregatorService.java      # Buggy production code (ArrayList + HashMap)
│       └── test/java/.../EventAggregatorServiceTest.java  # Tests + solution
└── ... (more examples)
```

## Common Async Problems Covered

### 1. Unhandled CompletableFuture Exceptions (Example 01)
**What**: Async operations fail without `.exceptionally()` or `.handle()`, causing silent data loss.

**Impact**: Orders/messages disappear without trace. No error logging, no retries, no fallback.

**Primary Detector**: `CompletableFutureExceptionDetector`
- Flags: "Unhandled exception in CompletableFuture chain"
- Detects: Exceptions that propagate without being caught

**Secondary Detectors**: 
- `RaceConditionDetector` - Unsynchronized access to shared state
- `VisibilityMonitor` - Inconsistent state visibility across threads

### 2. Memory Visibility / Missing volatile (Example 02)
**What**: Non-volatile shared fields cause threads to cache stale values and never see updates.

**Impact**: Graceful shutdown hangs, workers run indefinitely, resources leak.

**Primary Detector**: `VisibilityMonitor`
- Flags: "Field 'running' accessed by multiple threads without volatile keyword"
- Detects: Non-volatile fields read/written by multiple threads

**Secondary Detectors**: 
- `BusyWaitDetector` - Workers spinning indefinitely
- `ThreadLeakDetector` - Workers that never terminate

### 3. Shared Non-Thread-Safe Collection (Example 03)
**What**: `ArrayList` and `HashMap` shared across threads without synchronization.

**Impact**: Events are silently dropped, counts are wrong, and the application produces corrupted data without throwing any exception.

**Primary Detector**: `SharedCollectionDetector`
- Flags: "ArrayList: write operations from N threads — DATA CORRUPTION RISK!"
- Detects: Writes to non-thread-safe collections from multiple threads

**Secondary Detectors**:
- `ConcurrentModificationDetector` - Reads during concurrent writes
- `RaceConditionDetector` - Unsynchronized compound read-modify-write in `merge()`

**Fix**: Use `ConcurrentHashMap`, `CopyOnWriteArrayList`, or `Collections.synchronizedList()`

## How to Use These Examples

### For Learning
1. Start with `@Test` - observe tests pass
2. Change to `@AsyncTest(threads = 10, invocations = 50, detectAll = true)`
3. Run tests - watch them fail with detailed detector reports
4. Read the solution in the test file comments
5. Apply the fix - see tests pass again

### For Your Own Code
1. Identify similar patterns in your codebase
2. Write tests with `@AsyncTest` 
3. Let the library's detectors find the exact bugs
4. Apply the documented solutions

## Adding New Examples

When contributing new examples:
1. Create `example-NN-short-description/` directory
2. Include buggy production code in `src/main/java`
3. Include tests with `@Test` (passes) and commented `@AsyncTest` (fails)
4. Document the problem, root cause, and solution in comments
5. Update this README with the new example

## CI Integration

All examples run in CI to ensure they compile and pass with `@Test`:
```yaml
- name: Run example tests
  run: |
    for dir in example-*/; do
      mvn -Dmaven.repo.local=.m2/repository -f "$dir/pom.xml" test
    done
```

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with barriers exposes race conditions, visibility issues, and unhandled exceptions
3. **Always handle async exceptions**: Use `.exceptionally()`, `.handle()`, or equivalent
4. **Use volatile for shared flags**: Any field read/written by multiple threads needs `volatile` or `Atomic*` types
5. **Test under concurrent load**: What works sequentially often fails under real concurrent access
