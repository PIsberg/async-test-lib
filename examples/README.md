# Async Test Library - Examples

Real-world examples demonstrating common Java concurrency bugs that `@AsyncTest` finds but standard `@Test` misses.

## Available Examples

| # | Example | Primary Detector | Async Problem | Severity |
|---|---------|------------------|---------------|----------|
| 01 | [CompletableFuture Exception Handling](01-completablefuture-exception-handling/) | `CompletableFutureExceptionDetector` | Unhandled exceptions in async chains cause silent data loss | 🔴 Critical |
| 02 | [Visibility/Volatile Flag](02-visibility-volatile-flag/) | `VisibilityMonitor` | Missing `volatile` on shared flags causes threads to never see shutdown signals | 🔴 Critical |
| 03 | [Shared Non-Thread-Safe Collection](03-shared-collection/) | `SharedCollectionDetector` | ArrayList/HashMap shared across threads causes data loss and corruption | 🔴 Critical |
| 04 | [Virtual Thread Context Leak](04-virtual-thread-context-leak/) | `VirtualThreadContextLeakDetector` | ThreadLocal leaks in virtual threads cause memory leaks | 🟡 High |
| 09 | [Uncommitted Changes Detection](09-uncommitted-changes-detection/) | `UncommittedChangesDetector` | Untracked Git files break test reproducibility | 🟢 Low |
| 10 | [Shared Non-Thread-Safe Types](10-shared-non-thread-safe-types/) | `SharedMatcherDetector`, `SharedDecimalFormatDetector`, `SharedMessageDigestDetector` | Shared `Matcher`, `DecimalFormat`, and `MessageDigest` fields silently produce wrong results under concurrent load | 🔴 Critical |
| 11 | [Interrupt Swallowing](11-interrupt-swallowing/) | `InterruptSwallowingDetector` | `catch(InterruptedException)` without restoring the flag permanently suppresses cooperative cancellation | 🔴 Critical |
| 12 | [MDC Context Leak](12-mdc-context-leak/) | `MdcContextLeakDetector` | MDC entries not cleared at task end contaminate the next request on the reused thread | 🟡 High |
| 13 | [System Property Mutation](13-system-property-mutation/) | `SystemPropertyMutationDetector` | Concurrent `System.setProperty()` causes non-deterministic configuration and test pollution | 🟡 High |
| 14 | [Future Ignored](14-future-ignored/) | `FutureIgnoredDetector` | `submit()` result never inspected — task exceptions silently swallowed | 🔴 Critical |
| 15 | [Explicit GC](15-explicit-gc/) | `ExplicitGcDetector` | `System.gc()` triggers unpredictable STW pauses that corrupt concurrency timing tests | 🟡 High |
| 16 | [Deprecated Thread API](16-deprecated-thread-api/) | `DeprecatedThreadApiDetector` | `Thread.stop()`/`suspend()`/`resume()` are unsafe and removed in Java 20+ | 🔴 Critical |
| 17 | [Shared XML Parser](17-shared-xml-parser/) | `SharedXmlParserDetector` | `DocumentBuilder`/`Transformer` shared across threads causes corrupted parse results | 🔴 Critical |
| 18 | [Boxed Primitive Lock](18-boxed-primitive-lock/) | `BoxedPrimitiveLockDetector` | `synchronized` on cached `Integer`/`Boolean` acquires a JVM-global shared monitor | 🔴 Critical |
| 19 | [Shared TimeZone](19-shared-timezone/) | `SharedTimeZoneDetector` | `TimeZone.setRawOffset()` from multiple threads produces silently wrong date/time arithmetic | 🟡 High |
| 20 | [Uncaught Exception Handler](20-uncaught-exception-handler/) | `UncaughtExceptionHandlerDetector` | Threads without a custom `UncaughtExceptionHandler` discard thrown exceptions silently | 🟡 High |

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

### 9. Uncommitted Git Changes (Example 09)
**What**: Detects untracked or modified files in the Git repository that weren't committed.

**Impact**: Polluted classpath, non-reproducible test results, and environmental drift between local and CI.

**Primary Detector**: `UncommittedChangesDetector`
- Flags: "Git repository has uncommitted or untracked changes"
- Detects: `git status --porcelain` output showing M, A, D, R, C, or ?? files.

---

## Phase 11: Thread-Safety of Additional Types & Patterns (New in 0.10.0)

Five new detectors for JDK types that look thread-safe but silently corrupt state under
concurrent use. All five follow the same manual-recording pattern — test code registers
the shared object with the detector before exercising it.

### 1. SharedMatcherDetector (`detectSharedMatcher`)

**What**: Detects `java.util.regex.Matcher` instances accessed from multiple threads.

**Impact**: Wrong validation results — valid inputs rejected, invalid inputs accepted — with no exception thrown. `Pattern` is safe to share; `Matcher` holds mutable per-match cursor and group state.

**Usage**:
```java
@AsyncTest(threads = 8, detectSharedMatcher = true)
void testEmailValidation() {
    AsyncTestContext.sharedMatcherDetector()
        .recordAccess(service.getSharedMatcher(), "emailMatcher", Thread.currentThread());
    service.validateEmail(email);
}
```

**Fix**: Call `pattern.matcher(input)` inside each thread/method call rather than storing the `Matcher` as a field.

### 2. SharedDecimalFormatDetector (`detectSharedDecimalFormat`)

**What**: Detects `DecimalFormat` / `NumberFormat` instances accessed from multiple threads.

**Impact**: Garbled numeric output (e.g. `"1,2345.6"` for `1234.56`) or `ArrayIndexOutOfBoundsException` from the formatter's internal digit buffer. The numeric-formatting equivalent of the classic `SimpleDateFormat` bug.

**Usage**:
```java
@AsyncTest(threads = 8, detectSharedDecimalFormat = true)
void testAmountFormatting() {
    AsyncTestContext.sharedDecimalFormatDetector()
        .recordAccess(service.getAmountFormat(), "currencyFmt", Thread.currentThread());
    String result = service.formatAmount(amount);
}
```

**Fix**: `ThreadLocal<DecimalFormat>` or `new DecimalFormat(pattern)` per call.

### 3. WeakReferenceRaceDetector (`detectWeakReferenceRace`)

**What**: Detects two failure modes around `WeakReference` / `SoftReference`:
1. **ERROR** — result of `get()` used without a null check (referent may be collected between the call and the first dereference)
2. **WARN** — referent collected mid-test (some threads saw non-null, others saw null)

**Usage**:
```java
@AsyncTest(threads = 4, detectWeakReferenceRace = true)
void testWeakCache() {
    var d = AsyncTestContext.weakReferenceRaceDetector();
    Object val = weakRef.get();
    d.recordGet(weakRef, "cachedEntry", val, Thread.currentThread());
    if (val == null) {
        d.recordNullDereference(weakRef, "cachedEntry", Thread.currentThread());
    }
    // use val — may be null if referent was collected
}
```

### 4. StatefulLambdaDetector (`detectStatefulLambda`)

**What**: Detects lambda / `Runnable` / `Callable` instances that capture mutable containers (e.g. `int[]`, `Object[]`) and are **executed concurrently** while those captures are mutated.

**Impact**: Silently lost increments, corrupted array contents — the mutation looks like an atomic operation but involves multiple JVM instructions with no synchronization.

**Usage**:
```java
int[] counter = {0};
Runnable task = () -> { counter[0]++; };  // captures mutable int[]

@AsyncTest(threads = 4, detectStatefulLambda = true)
void testCounterTask() {
    var d = AsyncTestContext.statefulLambdaDetector();
    d.recordExecution(task, "counter-task", Thread.currentThread());
    d.recordCapturedMutation(task, "counter[0]", Thread.currentThread());
    task.run();
}
```

**Fix**: Replace `int[]` with `AtomicInteger` or `LongAdder`; or create a new lambda instance per task.

### 5. SharedMessageDigestDetector (`detectSharedMessageDigest`)

**What**: Detects `MessageDigest` instances accessed from multiple threads.

**Impact**: Wrong hash output with no exception — `update()` and `digest()` mutate the internal running buffer and byte count. Hash values differ silently from the expected result. One of the hardest concurrency bugs to reproduce in a debugger.

**Usage**:
```java
@AsyncTest(threads = 8, detectSharedMessageDigest = true)
void testFingerprint() {
    AsyncTestContext.sharedMessageDigestDetector()
        .recordAccess(service.getSha256(), "sha256", Thread.currentThread());
    String hash = service.fingerprint(data);
}
```

**Fix**: `MessageDigest.getInstance("SHA-256")` per thread, or `ThreadLocal<MessageDigest>`.

---

### Example 10: All Three Silent-Corruption Detectors Together

See [10-shared-non-thread-safe-types](10-shared-non-thread-safe-types/) for a complete
`DataProcessingService` that shares a `Matcher`, `DecimalFormat`, and `MessageDigest` as
class fields — a pattern common in services written before Java's thread-safety rules were
well understood. The example shows how `@Test` passes with false confidence and how each
`@AsyncTest` detector fires.

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

## Phase 12: Operational & Hygiene Concurrency Issues (New in 0.10.0)

### 1. InterruptSwallowingDetector
**What**: Detects `catch(InterruptedException)` blocks that swallow the signal without calling `Thread.currentThread().interrupt()` or rethrowing.

**Impact**: Executors, blocking operations, and shutdown handlers can no longer observe the interrupted state. Threads ignore cancellation requests, potentially looping forever.

**Usage**:
```java
@AsyncTest(threads = 4, detectInterruptSwallowing = true)
void testInterruptHandling() {
    try {
        Thread.sleep(100);
    } catch (InterruptedException e) {
        var d = AsyncTestContext.interruptSwallowingDetector();
        d.recordCatch(Thread.currentThread(), "MyWorker.run:42", false); // BAD
        // Fix: Thread.currentThread().interrupt(); d.recordCatch(..., true);
    }
}
```

**Fix**: Add `Thread.currentThread().interrupt()` before returning from every catch block, or rethrow as `InterruptedException`.

---

### 2. MdcContextLeakDetector
**What**: Detects SLF4J MDC (Mapped Diagnostic Context) entries not cleared at task end, leaking into the next task on the same pooled thread.

**Impact**: Log entries for request B carry request A's `requestId`, `userId`, or `traceId` — cross-request log pollution, compliance risks.

**Usage**:
```java
@AsyncTest(threads = 4, detectMdcContextLeak = true)
void testMdcCleanup() {
    var d = AsyncTestContext.mdcContextLeakDetector();
    Map<String,String> before = MDC.getCopyOfContextMap();
    d.recordTaskStart(Thread.currentThread(), before);
    try {
        MDC.put("requestId", "abc");
        processRequest();
    } finally {
        d.recordTaskEnd(Thread.currentThread(), MDC.getCopyOfContextMap());
        MDC.clear(); // Fix: add this line
    }
}
```

**Fix**: Call `MDC.clear()` (or `MDC.remove(key)`) in a `finally` block.

---

### 3. SystemPropertyMutationDetector
**What**: Detects concurrent `System.setProperty()` / `clearProperty()` calls during the test run.

**Impact**: Non-deterministic configuration state, test pollution that survives to subsequent test methods, data races on the shared `Properties` object.

**Usage**:
```java
@AsyncTest(threads = 4, detectSystemPropertyMutation = true)
void testConfig() {
    var d = AsyncTestContext.systemPropertyMutationDetector();
    d.recordSet("app.timeout", "5000", Thread.currentThread());
    System.setProperty("app.timeout", "5000");
}
```

**Fix**: Use environment variables, a test-scoped configuration map, or restore the property in `@AfterEach`.

---

### 4. FutureIgnoredDetector
**What**: Detects `Future` / `CompletableFuture` instances returned from `submit()` that are never inspected.

**Impact**: Exceptions thrown by submitted tasks are silently discarded. Failed background work appears to succeed.

**Usage**:
```java
@AsyncTest(threads = 4, detectFutureIgnored = true)
void testSubmit() {
    var d = AsyncTestContext.futureIgnoredDetector();
    Future<?> f = executor.submit(task);
    d.recordSubmit(f, "orderProcessor", Thread.currentThread());
    // Fix: d.recordInspect(f, Thread.currentThread()); f.get();
}
```

**Fix**: Always call `future.get()` (in a try-catch) or attach a `.whenComplete()` / `.exceptionally()` handler.

---

### 5. ExplicitGcDetector
**What**: Detects `System.gc()` or `Runtime.getRuntime().gc()` during concurrent execution.

**Impact**: Triggers an unpredictable stop-the-world pause, inflating latency measurements and introducing artificial timeouts that mask real concurrency bugs.

**Usage**:
```java
@AsyncTest(threads = 4, detectExplicitGc = true)
void testEviction() {
    var d = AsyncTestContext.explicitGcDetector();
    d.recordGcInvocation(Thread.currentThread(), "CacheManager.evict:58");
    System.gc(); // Flagged!
}
```

**Fix**: Remove explicit GC calls and rely on the JVM's automatic memory management.

---

### 6. DeprecatedThreadApiDetector
**What**: Detects calls to `Thread.stop()`, `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`, `Thread.countStackFrames()`.

**Impact**: `stop()` releases all monitors held by the target thread, breaking all invariants in shared state. `suspend/resume` are inherently deadlock-prone. All are removed or made no-ops in Java 20+.

**Usage**:
```java
@AsyncTest(threads = 4, detectDeprecatedThreadApi = true)
void testCancel() {
    var d = AsyncTestContext.deprecatedThreadApiDetector();
    d.recordApiUse("Thread.stop", Thread.currentThread()); // Flagged!
    workerThread.stop(); // DO NOT USE
}
```

**Fix**: Use cooperative cancellation (`volatile boolean cancelled`, `interrupt()`), `Semaphore`, `wait/notify`, or structured concurrency.

---

### 7. SharedXmlParserDetector
**What**: Detects `DocumentBuilder`, `SAXParser`, `Transformer`, and `XPath` instances accessed from multiple threads.

**Impact**: Corrupted parse results, `ConcurrentModificationException`s, or wrong XPath evaluations that are difficult to reproduce.

**Usage**:
```java
@AsyncTest(threads = 4, detectSharedXmlParser = true)
void testXmlProcessing() {
    var d = AsyncTestContext.sharedXmlParserDetector();
    d.recordAccess(sharedBuilder, "DocumentBuilder", Thread.currentThread());
    Document doc = sharedBuilder.parse(stream); // Flagged!
}
```

**Fix**: Use `ThreadLocal<DocumentBuilder>` or obtain a new instance per task (factories are thread-safe for `newXxx()`).

---

### 8. BoxedPrimitiveLockDetector
**What**: Detects `synchronized` blocks locking on cached boxed primitives.

**Impact**: Any code anywhere in the JVM synchronizing on the same value accidentally shares your monitor, causing surprising contention or deadlocks.

**Usage**:
```java
@AsyncTest(threads = 4, detectBoxedPrimitiveLock = true)
void testSync() {
    var d = AsyncTestContext.boxedPrimitiveLockDetector();
    Integer id = 42; // cached!
    d.recordLockAcquire(id, Thread.currentThread(), "OrderService:30");
    synchronized (id) { ... } // Flagged!
}
```

**Fix**: Use a dedicated `private final Object lock = new Object()`.

---

### 9. SharedTimeZoneDetector
**What**: Detects `TimeZone` instances mutated from multiple threads.

**Impact**: Non-deterministic timezone offsets and IDs — silently wrong date/time arithmetic that is notoriously hard to reproduce.

**Usage**:
```java
@AsyncTest(threads = 4, detectSharedTimeZone = true)
void testTz() {
    var d = AsyncTestContext.sharedTimeZoneDetector();
    d.recordMutation(sharedTz, "setRawOffset", Thread.currentThread());
    sharedTz.setRawOffset(3600_000); // Flagged!
}
```

**Fix**: Use `ZoneId` (java.time) which is immutable and thread-safe; or obtain a fresh `TimeZone.getTimeZone(id)` copy per thread.

---

### 10. UncaughtExceptionHandlerDetector
**What**: Detects threads started without a custom `UncaughtExceptionHandler` that subsequently throw.

**Impact**: The exception is only printed to stderr via the default thread-group handler. The submitting code has no way to detect the failure, and the thread pool silently replaces the dead thread.

**Usage**:
```java
@AsyncTest(threads = 4, detectUncaughtExceptionHandler = true)
void testWorker() {
    var d = AsyncTestContext.uncaughtExceptionHandlerDetector();
    Thread worker = new Thread(task); // no handler set!
    d.recordThreadStart(worker);
    worker.start();
    // if worker throws: d.recordUncaughtException(worker, throwable);
}
```

**Fix**: Call `worker.setUncaughtExceptionHandler(handler)` before `start()`, or use a `ThreadFactory` that installs a handler on every created thread.

---

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with barriers exposes race conditions, visibility issues, and unhandled exceptions
3. **Always handle async exceptions**: Use `.exceptionally()`, `.handle()`, or equivalent
4. **Use volatile for shared flags**: Any field read/written by multiple threads needs `volatile` or `Atomic*` types
5. **Test under concurrent load**: What works sequentially often fails under real concurrent access
