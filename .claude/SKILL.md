# async-test-lib — Usage Guide

**async-test-lib** is a JUnit 5 extension for stress-testing concurrent Java code. It forces real thread collisions using a `CyclicBarrier`, then runs 114 specialized detectors across 16 phases to identify exactly what went wrong — including three JDK 25/26 detectors (Phase 16) (`StableValue`, `StructuredTaskScope`, parallel `Gatherer`) you can also drive directly via AsyncTestContext accessors.

- Replaces `@Test` with `@AsyncTest` — zero other changes needed
- Requires Java 21 and JUnit 5 (Jupiter 6.0.3+)
- License: PolyForm Noncommercial (commercial use requires a separate license)

---

## Dependency

**Maven**
```xml
<dependency>
    <groupId>se.deversity.async-test-lib</groupId>
    <artifactId>async-test-lib</artifactId>
    <version>1.7.0-RC2</version>
    <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
testImplementation("se.deversity.async-test-lib:async-test-lib:1.7.0-RC2")
```

---

## Quickstart

```java
import se.deversity.asynctest.AsyncTest;

class CounterTest {

    private int counter = 0; // BUG: not thread-safe

    @AsyncTest    // all detectors enabled by default
    void increment() {
        counter++;            // Caught: race condition / atomicity violation
    }
}
```

`@AsyncTest` launches `threads` concurrent threads per invocation, repeats `invocations` times, and reports exactly which detector fired and why. The same test with plain `@Test` would pass silently.

> **Default behavior changed in 1.x:** `detectAll = true` is now the default, so bare `@AsyncTest` enables every detector. Use `excludes = {…}` to opt out, or set `detectAll = false` and turn on detectors individually.

---

## @AsyncTest — core parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 10 | Concurrent threads per invocation round |
| `threadCounts` | int[] | `{}` | **Schedule matrix.** When non-empty, one JUnit invocation per entry runs with that thread count; ignored when empty. Use to sweep `{2,4,8,16,32}` cheaply (1.6.0+) |
| `invocations` | int | 100 | Number of invocation rounds |
| `useVirtualThreads` | boolean | true | Use Project Loom virtual threads (Java 21+) |
| `timeoutMs` | long | 5000 | Milliseconds before timeout (triggers deadlock analysis) |
| `virtualThreadStressMode` | String | `"OFF"` | `OFF` / `LOW` / `MEDIUM` / `HIGH` / `EXTREME` — pins carrier threads to increase contention |
| `preset` | `Preset` | `Preset.ALL` | **Curated detector bundle.** `ALL` / `STRICT` / `ESSENTIALS` / `CI_FAST` / `NONE`. Overrides `detectAll` for any value other than `ALL` (1.6.0+) |
| `detectAll` | boolean | **true** | Enable every detector at once. Honored when `preset = ALL`; ignored otherwise. Set `false` to only run individually-flagged detectors |
| `excludes` | DetectorType[] | `{}` | Detectors to skip — layers on top of any preset |
| `replaySeed` | long | 0 | **Per-round RNG seed.** `0` = fresh seed per round, printed on failure for paste-and-reproduce. Set explicitly to reproduce a failing schedule (1.6.0+) |
| `enableBenchmarking` | boolean | false | Record timing data for regression detection |
| `benchmarkRegressionThreshold` | double | 0.2 | Regression threshold as a decimal (0.2 = 20%) |
| `failOnBenchmarkRegression` | boolean | false | Fail the test if a regression is detected |

Every individual detector flag (the full `detect*` / `validate*` / `monitor*` list further down) defaults to `true` and is gated by `detectAll`. To run a tiny subset, set `detectAll = false` and enable only what you need — or use `preset = Preset.ESSENTIALS` / `Preset.CI_FAST` for curated bundles.

`virtualThreadStressMode` thread budgets: `LOW` ≈ 100, `MEDIUM` ≈ 1,000, `HIGH` ≈ 10,000, `EXTREME` ≈ 100,000+ (may need heap adjustment).

### Preset bundles (1.6.0+)

| Preset | Detectors | Use case |
|--------|-----------|----------|
| `ALL` | every detector (default) | "Tell me everything." Default behavior; equivalent to `detectAll = true` |
| `STRICT` | every detector | Same set as `ALL`, named explicitly for libraries that want "every check on by name" independent of what `ALL` evolves to |
| `ESSENTIALS` | 12 high-signal: deadlocks, races, atomicity, lock/thread leaks, interrupt-mishandling, conc-modification, CompletableFuture errors, resource leaks, uncaught-exception handlers | Everyday CI on application code — covers the bugs production teams hit most often |
| `CI_FAST` | 6 detectors: deadlocks, races, atomicity, lock leaks, conc-modification, CompletableFuture errors | PR gates where ESSENTIALS would be too slow (omits visibility / livelocks / interrupt-mishandling) |
| `NONE` | none | Pure N×M stress execution with no diagnostic machinery; useful when you only want concurrent execution |

---

## Common patterns

### Just turn it on
```java
@AsyncTest
void testMyService() {
    myService.process(request);   // every detector active
}
```

### Curated preset for everyday CI (1.6.0+)
```java
import se.deversity.asynctest.Preset;

@AsyncTest(preset = Preset.ESSENTIALS)
void testMyService() {
    myService.process(request);   // 12 high-signal detectors; fast
}
```

### Sweep thread counts to find the contention sweet spot (1.6.0+)
```java
@AsyncTest(threadCounts = {2, 4, 8, 16, 32, 64})  // 6 separate JUnit invocations
void testRacyCache() {
    cache.put(key, value);  // race may only surface at 16+ threads
}
```
Each entry produces a JUnit invocation with display name `[AsyncTest] N threads x M invocations`. Pair with a preset to scope detectors per run.

### Async test body — await a `CompletionStage` (1.6.0+)
```java
import se.deversity.asynctest.AsyncAssert;
import java.time.Duration;

@AsyncTest(threads = 8)
void testAsyncPipeline() {
    CompletableFuture<String> stage = service.processAsync(payload);
    String result = AsyncAssert.awaitAsync(stage, Duration.ofSeconds(5));
    assertEquals("ok", result);
}
```
`awaitAsync` blocks until the chain completes and unwraps `ExecutionException` so user assertions/exceptions surface as their original types. This is the supported way to exercise async APIs from `@AsyncTest`, since JUnit Jupiter rejects non-void `@TestTemplate` return types at discovery.

### Reproduce a flaky failure with `replaySeed` (1.6.0+)
```java
import se.deversity.asynctest.AsyncTestContext;
import java.util.Random;

@AsyncTest                          // 1st run: "[AsyncTest] Failure with replaySeed=42424242L"
// @AsyncTest(replaySeed = 42424242L)  // re-run with the printed seed
void randomisedWorkload() {
    var rng = new Random(AsyncTestContext.replaySeed());
    Thread.sleep(rng.nextInt(10));   // randomised jitter is now deterministic
    service.handle(payload(rng));
}
```
The runner draws a fresh `long` seed per round (visible to all N workers in that round via `AsyncTestContext.replaySeed()`) and prints it on failure. Setting `replaySeed = N` pins every round to `N`. Does NOT make thread scheduling deterministic — gives RNG-driven test inputs a stable starting point.

### Scoped listener (no JVM-wide leak) (1.6.0+)
```java
try (var ignored = AsyncTestListenerRegistry.registerScoped(myListener)) {
    runMyAsyncTest();   // myListener fires only inside this block
}
// automatic unregister on close
```
Or save/restore the whole registry around a block:
```java
var snap = AsyncTestListenerRegistry.snapshot();
try { ... } finally { AsyncTestListenerRegistry.restoreSnapshot(snap); }
```

### Race condition — narrowed
```java
@AsyncTest(
    threads = 20,
    invocations = 50,
    detectAll = false,
    detectRaceConditions = true,
    detectAtomicityViolations = true
)
void testCounter() {
    counter++; // Detected: non-atomic read-modify-write
}
```

### Deadlock detection (always on)
```java
@AsyncTest(threads = 10, invocations = 10, timeoutMs = 2000)
void testLockOrder() {
    synchronized (lockA) {
        synchronized (lockB) { /* work */ }
    }
    // Another thread acquires B then A → deadlock → detected on timeout
}
```

### Opt out of noisy detectors
```java
import se.deversity.asynctest.DetectorType;

@AsyncTest(
    threads = 16,
    invocations = 200,
    excludes = { DetectorType.BUSY_WAITING, DetectorType.FALSE_SHARING }
)
void testMyService() {
    myService.process(request);
}
```

### Virtual thread stress (expose pinning bugs)
```java
@AsyncTest(
    threads = 50,
    invocations = 100,
    useVirtualThreads = true,
    virtualThreadStressMode = "HIGH"
)
void testVirtualThreadSafety() {
    service.handleRequest();
}
```

### CompletableFuture exception detection
```java
@AsyncTest(
    threads = 10,
    invocations = 50,
    detectAll = false,
    detectCompletableFutureExceptions = true,
    detectCompletableFutureCompletionLeaks = true,
    detectCompletableFutureChainIssues = true,
    detectCFCommonPoolBlocking = true
)
void testAsyncPipeline() {
    CompletableFuture<Result> future = service.processAsync(item);
    // Unhandled exceptions, abandoned futures, missing handlers, common-pool blocking — all caught
}
```

### Benchmarking with regression guard
```java
@AsyncTest(
    threads = 20,
    invocations = 1000,
    enableBenchmarking = true,
    benchmarkRegressionThreshold = 0.15, // fail if 15% slower than baseline
    failOnBenchmarkRegression = true
)
void testThroughput() {
    service.handle(request);
}
```
Baselines are stored in `target/benchmark-data/baseline-store.dat`. Override location with `-Dbenchmark.store.path=<path>`. Force a baseline update with `-Dbenchmark.update=true`.

---

## Per-invocation lifecycle hooks

JUnit's `@BeforeEach` / `@AfterEach` run once for the entire `@AsyncTest`. Use the library's own hooks to run logic before/after **each invocation round**:

```java
class SharedStateTest {

    private ConcurrentHashMap<String, Integer> map;

    @BeforeEachInvocation     // runs before each of the 100 rounds
    void reset() {
        map = new ConcurrentHashMap<>();
    }

    @AsyncTest(threads = 10, invocations = 100)
    void testConcurrentPut() {
        map.put(UUID.randomUUID().toString(), 1);
    }

    @AfterEachInvocation      // runs after each of the 100 rounds
    void assertNoLoss() {
        assertTrue(map.size() <= 10);
    }
}
```

| Hook | When it runs | How many times |
|------|-------------|----------------|
| `@BeforeAll` | Once before all invocations | 1 |
| `@BeforeEach` | Once before the entire @AsyncTest | 1 |
| `@BeforeEachInvocation` | Before each invocation round | `invocations` |
| `@AfterEachInvocation` | After each invocation round | `invocations` |
| `@AfterEach` | Once after the entire @AsyncTest | 1 |
| `@AfterAll` | Once after all invocations | 1 |

---

## Full detector reference

All detector flags below default to `true` and are gated by `detectAll`. Set `detectAll = false` to run only the ones you turn on explicitly, or list `DetectorType` values in `excludes` to skip specific ones.

### Phase 1 — Core
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectDeadlocks` | `DEADLOCKS` | Thread deadlocks with lock-chain analysis (always on) |
| `detectVisibility` | `VISIBILITY` | Missing `volatile`, stale memory reads |
| `detectLivelocks` | `LIVELOCKS` | Threads spinning without progress |

### Phase 2 — Core advanced
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectFalseSharing` | `FALSE_SHARING` | Cache-line contention between threads |
| `detectWakeupIssues` | `WAKEUP_ISSUES` | Spurious wakeups, lost `notify()` signals |
| `validateConstructorSafety` | `CONSTRUCTOR_SAFETY` | Object published before fully constructed |
| `detectABAProblem` | `ABA_PROBLEM` | Lock-free ABA hazard |
| `validateLockOrder` | `LOCK_ORDER` | Inconsistent lock acquisition order |
| `monitorSynchronizers` | `SYNCHRONIZERS` | Barrier/phaser progression issues |
| `monitorThreadPool` | `THREAD_POOL` | Executor saturation and unhealthy state |
| `detectMemoryOrderingViolations` | `MEMORY_ORDERING` | CPU reordering issues |
| `monitorAsyncPipeline` | `ASYNC_PIPELINE` | Event flow tracking |
| `monitorReadWriteLockFairness` | `READ_WRITE_LOCK_FAIRNESS` | Writer starvation |

### Phase 2 — Monitors
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `monitorSemaphore` | `SEMAPHORE` | Permit leaks, over-release |
| `detectCompletableFutureExceptions` | `COMPLETABLE_FUTURE_EXCEPTIONS` | Unhandled async exceptions |
| `detectCompletableFutureCompletionLeaks` | `COMPLETABLE_FUTURE_COMPLETION_LEAKS` | Futures that are never completed |
| `detectVirtualThreadPinning` | `VIRTUAL_THREAD_PINNING` | Virtual threads pinned to carrier thread |
| `detectThreadPoolDeadlocks` | `THREAD_POOL_DEADLOCK` | Nested task submission deadlocks |
| `detectConcurrentModifications` | `CONCURRENT_MODIFICATIONS` | Collection mutated during iteration |
| `detectLockLeaks` | `LOCK_LEAKS` | Locks acquired but never released |
| `detectSharedRandom` | `SHARED_RANDOM` | `java.util.Random` used across threads |
| `detectBlockingQueueIssues` | `BLOCKING_QUEUE` | Queue saturation or imbalance |
| `detectConditionVariableIssues` | `CONDITION_VARIABLES` | Lost signals, stuck waiters |
| `detectSimpleDateFormatIssues` | `SIMPLE_DATE_FORMAT` | Non-thread-safe `SimpleDateFormat` |
| `detectParallelStreamIssues` | `PARALLEL_STREAMS` | Stateful lambdas and side effects |
| `detectResourceLeaks` | `RESOURCE_LEAKS` | `AutoCloseable` not closed |

### Phase 2 — Additional concurrency
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectCountDownLatchIssues` | `COUNTDOWN_LATCH` | Timeout, missing/extra `countDown()` |
| `detectCyclicBarrierIssues` | `CYCLIC_BARRIER` | Timeout, broken barriers |
| `detectReentrantLockIssues` | `REENTRANT_LOCK` | Starvation, unfair acquisition |
| `detectVolatileArrayIssues` | `VOLATILE_ARRAY` | Non-volatile array element access |
| `detectDoubleCheckedLocking` | `DOUBLE_CHECKED_LOCKING` | Broken DCL without `volatile` |
| `detectWaitTimeout` | `WAIT_TIMEOUT` | `wait()` without a timeout argument |
| `detectLockContention` | `LOCK_CONTENTION` | Monitors with high blocked-acquire ratio |
| `detectSynchronizedNonFinal` | `SYNCHRONIZED_NON_FINAL` | `synchronized` on a non-`final` field |
| `detectMissedSignals` | `MISSED_SIGNAL` | `notify()` with no thread waiting |
| `detectLazyInitRace` | `LAZY_INIT_RACE` | Multi-thread lazy init without proper guards |

### Phase 2 — Advanced concurrency utilities
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectPhaserIssues` | `PHASER` | Missing `arrive()`, timeouts |
| `detectStampedLockIssues` | `STAMPED_LOCK` | Unvalidated optimistic reads |
| `detectExchangerIssues` | `EXCHANGER` | Timeouts, missing exchange partners |
| `detectScheduledExecutorIssues` | `SCHEDULED_EXECUTOR` | Missing shutdown, long-running tasks |
| `detectForkJoinPoolIssues` | `FORK_JOIN_POOL` | Fork without join |
| `detectThreadFactoryIssues` | `THREAD_FACTORY` | Missing uncaught exception handlers |

### Phase 3 — Behavioral
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectRaceConditions` | `RACE_CONDITIONS` | Unsynchronized cross-thread field access |
| `detectThreadLocalLeaks` | `THREAD_LOCAL_LEAKS` | Missing `ThreadLocal.remove()` |
| `detectBusyWaiting` | `BUSY_WAITING` | Tight spin loops |
| `detectAtomicityViolations` | `ATOMICITY_VIOLATIONS` | Check-then-act compound operations |
| `detectInterruptMishandling` | `INTERRUPT_MISHANDLING` | Swallowed `InterruptedException` |

### Phase 4 — Infrastructure & resource management
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectThreadLeaks` | `THREAD_LEAKS` | Threads created but never terminated |
| `detectSleepInLock` | `SLEEP_IN_LOCK` | `Thread.sleep()` while holding a lock |
| `detectUnboundedQueue` | `UNBOUNDED_QUEUE` | `BlockingQueue` with no capacity bound |
| `detectThreadStarvation` | `THREAD_STARVATION` | Tasks waiting excessively for execution |

### Phase 5 — Thread-safety of common types
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectCalendarIssues` | `CALENDAR` | Shared `Calendar` accessed by multiple threads |
| `detectSharedCollections` | `SHARED_COLLECTIONS` | `ArrayList`/`HashMap`/`HashSet`/etc. used concurrently without synchronization |
| `detectTimerIssues` | `TIMER` | `java.util.Timer` failures (uncaught exception kills all tasks) |
| `detectCopyOnWriteCollectionIssues` | `COPY_ON_WRITE_COLLECTIONS` | `CopyOnWriteArrayList`/`Set` in write-heavy paths |
| `detectStringBuilderIssues` | `STRING_BUILDER` | `StringBuilder` mutated by multiple threads |

### Phase 6 — Virtual thread concurrency (Java 21+)
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectStructuredConcurrencyIssues` | `STRUCTURED_CONCURRENCY` | Unclosed `StructuredTaskScope`, missing `join()`, etc. |
| `detectVirtualThreadContextLeaks` | `VIRTUAL_THREAD_CONTEXT_LEAKS` | `ThreadLocal` set in virtual threads but never removed |
| `detectScopedValueMisuse` | `SCOPED_VALUE` | `ScopedValue.get()` outside an active binding |
| `detectVirtualThreadCpuBoundTasks` | `VIRTUAL_THREAD_CPU_BOUND` | Virtual threads running long CPU-bound work without yielding |
| `detectVirtualThreadCarrierExhaustion` | `VIRTUAL_THREAD_CARRIER_EXHAUSTION` | Blocked virtual threads exceeding carrier capacity |

### Phase 7 — High-level concurrency patterns
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectHttpClientIssues` | `HTTP_CLIENT` | Unclosed responses, pool exhaustion, requests never awaited |
| `detectStreamClosing` | `STREAM_CLOSING` | `InputStream`/`OutputStream`/`Reader`/`Writer` opened but never closed |
| `detectCacheConcurrency` | `CACHE_CONCURRENCY` | `HashMap`/`LinkedHashMap` used as cache without synchronization, cache stampede |
| `detectCompletableFutureChainIssues` | `COMPLETABLEFUTURE_CHAIN` | Missing `.exceptionally()`/`.handle()` in async chains |

### Phase 8 — Lifecycle & structural correctness
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectExecutorShutdown` | `EXECUTOR_SHUTDOWN` | `ExecutorService` never shut down, or shut down without `awaitTermination()` |
| `detectMutableMapKeys` | `MUTABLE_MAP_KEY` | Mutable objects used as `HashMap`/`HashSet` keys, then mutated |
| `detectNestedMonitorLockout` | `NESTED_MONITOR_LOCKOUT` | Blocking call (`wait()`, `Future.get()`, `Lock.lock()`) while holding another monitor |
| `detectLockDowngrade` | `LOCK_DOWNGRADE` | Illegal read-to-write upgrade on `ReentrantReadWriteLock` |
| `detectInheritableThreadLocalMisuse` | `INHERITABLE_THREAD_LOCAL` | `InheritableThreadLocal` used in pooled threads (inheritance happens at thread creation, not submission) |

### Phase 9 — Repository & environment state
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectUncommittedChanges` | `UNCOMMITTED_CHANGES` | Untracked or uncommitted Git files when the test completes |

### Phase 10 — API traps & subtle concurrency bugs
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectThreadLocalContamination` | `THREAD_LOCAL_CONTAMINATION` | `ThreadLocal` from one task read by the next task on the same pooled thread |
| `detectAtomicNonAtomicUpdates` | `ATOMIC_NON_ATOMIC_UPDATE` | `get()`-then-`set()` on `AtomicInteger`/`AtomicLong` without `compareAndSet()` |
| `detectSynchronizedCollectionIteration` | `SYNCHRONIZED_COLLECTION_ITERATION` | Iterating `Collections.synchronizedList`/`Map`/`Set` without holding the wrapper lock |
| `detectSharedFormatter` | `SHARED_FORMATTER` | `Formatter`/`PrintWriter`/`PrintStream` shared without synchronization |
| `detectConcurrentMapComputeRecursion` | `CONCURRENT_MAP_COMPUTE_RECURSION` | Recursive `ConcurrentHashMap.computeIfAbsent`/`compute`/`merge` on the same key |
| `detectSynchronizedOnLiteral` | `SYNCHRONIZED_ON_LITERAL` | `synchronized` on interned `String` or cached `Integer`/`Long` (JVM-shared monitors) |
| `detectPublicLockExposure` | `PUBLIC_LOCK_EXPOSURE` | `synchronized(this)` while `this` is publicly accessible |
| `detectForkJoinTaskBlocking` | `FORK_JOIN_TASK_BLOCKING` | Blocking calls inside `ForkJoinTask` bodies — starves carrier threads |
| `detectOptimisticReadValidation` | `OPTIMISTIC_READ_VALIDATION` | `StampedLock.tryOptimisticRead()` data used without `validate(stamp)` |
| `detectCFCommonPoolBlocking` | `CF_COMMON_POOL_BLOCKING` | Blocking ops inside `CompletableFuture` stages on the common `ForkJoinPool` |

### Phase 11 — Thread-safety of additional types & patterns
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectSharedMatcher` | `SHARED_MATCHER` | `java.util.regex.Matcher` shared across threads (mutable match state) |
| `detectSharedDecimalFormat` | `SHARED_DECIMAL_FORMAT` | `DecimalFormat`/`NumberFormat` shared concurrently |
| `detectWeakReferenceRace` | `WEAK_REFERENCE_RACE` | `WeakReference.get()` results used without null check, or referent collected mid-test |
| `detectStatefulLambda` | `STATEFUL_LAMBDA` | Lambda/`Runnable`/`Callable` capturing mutable containers (`int[]`, `Object[]`) executed concurrently |
| `detectSharedMessageDigest` | `SHARED_MESSAGE_DIGEST` | `MessageDigest` accessed concurrently — silently corrupts hash state |

### Phase 12 — Operational & hygiene concurrency issues
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectInterruptSwallowing` | `INTERRUPT_SWALLOWING` | `catch (InterruptedException)` blocks that neither rethrow nor restore the interrupt flag |
| `detectMdcContextLeak` | `MDC_CONTEXT_LEAK` | SLF4J MDC entries not cleared at task end |
| `detectSystemPropertyMutation` | `SYSTEM_PROPERTY_MUTATION` | Concurrent `System.setProperty`/`clearProperty` calls during the run |
| `detectFutureIgnored` | `FUTURE_IGNORED` | `Future` from `submit()` never inspected — failed-task exceptions silently swallowed |
| `detectExplicitGc` | `EXPLICIT_GC` | `System.gc()`/`Runtime.gc()` calls — corrupt timing measurements |
| `detectDeprecatedThreadApi` | `DEPRECATED_THREAD_API` | `Thread.stop()`/`suspend()`/`resume()`/`destroy()`/`countStackFrames()` |
| `detectSharedXmlParser` | `SHARED_XML_PARSER` | `DocumentBuilder`/`SAXParser`/`Transformer`/`XPath` shared concurrently |
| `detectBoxedPrimitiveLock` | `BOXED_PRIMITIVE_LOCK` | `synchronized` on cached `Integer`/`Long`/`Boolean.TRUE`/interned `String` |
| `detectSharedTimeZone` | `SHARED_TIMEZONE` | Mutating shared `TimeZone` via `setRawOffset`/`setID` from multiple threads |
| `detectUncaughtExceptionHandler` | `UNCAUGHT_EXCEPTION_HANDLER` | Threads started without a custom `UncaughtExceptionHandler` that subsequently throw |

### Phase 13 — Additional concurrency-bug categories (1.6.0+)
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectDaemonThreadHygiene` | `DAEMON_THREAD_HYGIENE` | Non-daemon `Thread` instances still alive at analyze time — they block JVM exit and can hang the test process. Distinct from `THREAD_LEAKS` which counts live threads regardless of daemon flag. Detector class: `DaemonThreadHygieneDetector`. |
| `detectNotifyWithoutMonitor` | `NOTIFY_WITHOUT_MONITOR` | `notify()`/`notifyAll()` attempts (declared via `recordNotifyAttempt`) when the calling thread does not hold the monitor — would throw `IllegalMonitorStateException` at runtime and leave `wait()`-ers blocked. Complements `MISSED_SIGNAL` (which catches notifies with no waiter). |
| `detectSharedSecureRandom` | `SHARED_SECURE_RANDOM` | `java.security.SecureRandom` instances accessed from multiple threads. Thread safety is provider-dependent (SHA1PRNG, NativePRNG, Bouncy Castle, custom SPIs all differ). Distinct from `SHARED_RANDOM` which covers `java.util.Random` only. Report carries algorithm + provider names. |
| `detectWeakHashMapShared` | `WEAK_HASH_MAP_SHARED` | `WeakHashMap` or `IdentityHashMap` instances accessed from multiple threads. Both have additional concurrency hazards beyond regular `HashMap`: GC-driven entry removal mutates the table on every get/put; linear probing can drop or duplicate entries under concurrent puts. |
| `detectJdbcConnectionShared` | `JDBC_CONNECTION_SHARED` | `java.sql.Connection`/`Statement`/`PreparedStatement`/`ResultSet` accessed from multiple threads. JDBC spec does not require any of these to be thread-safe; most drivers (Postgres/MySQL/Oracle) document one-thread-per-Connection. Concurrent use produces mixed cursors, protocol corruption, or transaction leakage. |

### Phase 14 — Additional thread-unsafe primitives & publication hazards (1.7.0+)
| Annotation field | DetectorType | What it catches |
|-----------------|-------------|-----------------|
| `detectSharedStatefulCrypto` | `SHARED_STATEFUL_CRYPTO` | `javax.crypto.Cipher`, `javax.crypto.Mac`, and `java.security.Signature` instances accessed from multiple threads. Unlike `MessageDigest`, these carry mutable per-operation state across `init → update → doFinal`/`sign`/`verify`; interleaved calls corrupt ciphertext or fold bytes from two callers into one MAC/signature that verifies for neither. Instrument via `AsyncTestContext.sharedStatefulCryptoDetector().recordAccess(cipher/mac/signature, name, thread)`. Sibling of `SHARED_MESSAGE_DIGEST` / `SHARED_SECURE_RANDOM`. |
| `detectConcurrentMapCheckThenAct` | `CONCURRENT_MAP_CHECK_THEN_ACT` | Non-atomic check-then-act on a `ConcurrentMap` (`containsKey`/`get` then `put`) performed by multiple threads against the same map+key — a lost-update race. Each op is atomic but the compound sequence is not; use `putIfAbsent`/`computeIfAbsent`/`compute`/`merge`. Instrument via `recordCheckThenAct(map, key, operation, thread)`. Distinct from `ATOMIC_NON_ATOMIC_UPDATE` (`Atomic*` types) and `CONCURRENT_MAP_COMPUTE_RECURSION` (re-entrancy inside `computeIfAbsent`). |
| `detectSharedDeflater` | `SHARED_DEFLATER` | `java.util.zip.Deflater`/`Inflater` accessed from multiple threads. Both wrap a stateful native zlib stream and are not thread-safe; concurrent use corrupts output or crashes when one thread calls `end()` mid-stream. Instrument via `recordAccess(deflater/inflater, name, thread)`. |
| `detectThisEscape` | `THIS_ESCAPE` | A constructor that publishes `this` before returning (starts a thread, registers a listener, stores into shared state), exposing a partially-constructed object — no final-field visibility guarantee, fields may still be default. Instrument via `recordConstructorEscape(this, how, thread)`, plus optional `recordExternalAccess(instance, thread)` / `recordConstructionComplete(instance)`. MEDIUM, escalated to HIGH when another thread observes it before completion. |
| `detectThreadLocalRandomMisuse` | `THREAD_LOCAL_RANDOM_MISUSE` | A `ThreadLocalRandom.current()` reference cached (e.g. in a field) and used from a thread other than the one that obtained it, defeating its per-thread isolation. Instrument via `recordObtain(rng, name, thread)` then `recordUse(rng, thread)`. Distinct from `SHARED_RANDOM` (`java.util.Random`) and `SHARED_SECURE_RANDOM`. |

### JDK 25/26 preview-era detectors — standalone (1.7.0+)

These three detectors target concurrency features introduced/finalized in JDK 24–26.
They are **not** wired into the `@AsyncTest` `detectAll` pipeline (a pipeline detector
needs a `DetectorType` enum constant, and that enum is a locked file). Use them
**directly**: instantiate, record events from your test body, then call `analyze()`
and assert on the report. They live in `se.deversity.asynctest.diagnostics` and are
thread-safe (`ConcurrentHashMap`-backed), so a single instance can be shared across
the worker threads of an `@AsyncTest`.

| Detector | JDK feature | Record API | What it catches |
|----------|-------------|------------|-----------------|
| `StableValueMisuseDetector` | `StableValue` — JEP 502 (preview JDK 25 → 26) | `recordSet(name, thread)`, `recordRead(name, thread)`, `recordSupplierStart/End(name, thread)` | read-before-set (`NoSuchElementException`), double-set (lost update / `IllegalStateException`), reentrant `orElseSet` supplier, set-contention |
| `StructuredTaskScopeMisuseDetector` | `StructuredTaskScope` — JEP 505 (preview JDK 25 → final JDK 26) | `recordScopeOpened(id, owner)`, `recordFork(id, subtaskId, thread)`, `recordJoin(id, thread)`, `recordResultRead(id, subtaskId, thread)`, `recordScopeClosed(id, thread)` | fork-after-join, `Subtask.get()` before join, owner-confinement (`WrongThreadException`), close-without-join (subtasks cancelled) |
| `GathererConcurrencyMisuseDetector` | Stream Gatherers — JEP 485 (final JDK 24) | `registerGatherer(name, hasCombiner, parallel)`, `recordIntegrate(name, thread)` | stateful gatherer on a parallel stream with no combiner (lost results), concurrent-integrator shared-state race |

```java
import se.deversity.asynctest.diagnostics.StableValueMisuseDetector;

@AsyncTest(threads = 16, invocations = 50, detectAll = false)  // drive the contention
void stableValueLazyInit() {
    detector.recordSupplierStart("CONFIG", Thread.currentThread());
    Config c = configHolderUnderTest();           // models StableValue.orElseSet(...)
    detector.recordSupplierEnd("CONFIG", Thread.currentThread());
    detector.recordRead("CONFIG", Thread.currentThread());
}

@AfterEach
void assertNoMisuse() {
    var report = detector.analyze();
    assertFalse(report.hasIssues(), report::toString);   // shared field: private final ...Detector detector = new ...();
}
```

Each `analyze()` returns a typed `*Report` with `hasIssues()`, per-category issue
lists, and a `toString()` that includes a `📚 LEARNING` block — same shape as every
other detector report.

---

## Observability — AsyncTestListener

Register a listener to receive events from every test run:

```java
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;

public class MyListener implements AsyncTestListener {
    @Override
    public void onInvocationStarted(int round, int threads) { }

    @Override
    public void onInvocationCompleted(int round, long durationMs) { }

    @Override
    public void onTestFailed(Throwable cause) { }

    @Override
    public void onDetectorReport(String detectorName, String report) {
        System.out.println("[" + detectorName + "] " + report);
    }

    @Override
    public void onTimeout(long timeoutMs) { }
}

// Register once, e.g., in @BeforeAll or a static initializer
AsyncTestListenerRegistry.register(new MyListener());
```

Listeners may be called from multiple threads concurrently — implementations must be thread-safe.

---

## Structured violations & formatters (1.6.0+)

Detector findings are also exposed as structured `Violation` records in `se.deversity.asynctest.report`. Two built-in formatters consume them:

```java
import se.deversity.asynctest.report.*;

List<Violation> findings = // from a detector adapter or the SPI registry
String md   = new MarkdownFormatter().format(findings);   // PR comments, CI logs
String json = new JsonFormatter().format(findings);       // dashboards, SARIF, IDE plugins
```

Each `Violation` carries `detector`, `severity` (`IssueSeverity`), `message`, `sites: List<SiteCapture.Site>`, `attributes: Map<String,Object>`, and `when: Instant`. The legacy `toString()` string reports continue alongside.

**Source-line attribution.** Violations now include an `Access sites:` block pointing at the user-code line that produced the issue (e.g. `MyService.encrypt(MyService.java:42)`). Currently emitted by `SharedMessageDigestDetector` as the canary; rolling out incrementally to the other 90+ detectors. `SiteCapture.capture()` is the shared helper for migrating each one.

---

## Detector SPI (1.6.0+)

For custom detectors and tooling: `se.deversity.asynctest.spi.{Detector, DetectorFactory, DetectorRegistry}`. Register a `DetectorFactory` via `META-INF/services/se.deversity.asynctest.spi.DetectorFactory` and it's discovered by `ServiceLoader`:

```java
public final class MyDetectorFactory implements DetectorFactory {
    @Override public DetectorType type() { return DetectorType.SOMETHING; }
    @Override public boolean isEnabledFor(AsyncTestConfig cfg) { return cfg.detectSomething; }
    @Override public Detector create(AsyncTestConfig cfg) { return new MyDetector(); }
}
```

```java
DetectorRegistry reg = DetectorRegistry.build(config);
List<Violation> all = reg.analyzeAll();
MyDetector mine = reg.get(MyDetector.class);
```

The legacy `se.deversity.asynctest.DetectorRegistry` continues to power the existing 90+ detectors; the SPI registry coexists for new detectors and incremental migrations.

---

## Tips

- **Bare `@AsyncTest` is the right starting point** — every detector is on by default in 1.x. Narrow with `excludes`, or set `detectAll = false` and turn on individual flags only after you understand the failures.
- **Increase `invocations` before `threads`** — more rounds give detectors more chances to observe bad interleavings. 200–1000 invocations is a good baseline.
- **Use `@BeforeEachInvocation` to reset shared state** between rounds; not doing so causes round N's leftover state to pollute round N+1.
- **`timeoutMs`** controls how long a round can run before deadlock analysis fires. Lower it for tests that should complete quickly.
- **`virtualThreadStressMode = "HIGH"`** is the fastest way to reproduce virtual thread pinning bugs; leave it `OFF` for normal tests (it adds overhead).
- **Detector reports follow a consistent what / why / fix layout** since 1.3.0 — read top-to-bottom for triage.
- **Benchmark baselines** are per-machine, per-environment. Commit `target/benchmark-data/` to get stable CI regression detection, or use `-Dbenchmark.store.path` to point at a stable location outside `target/`.
