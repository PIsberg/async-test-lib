# async-test-lib — Usage Guide

**async-test-lib** is a JUnit 5 extension for stress-testing concurrent Java code. It forces real thread collisions using a `CyclicBarrier`, then runs 90+ specialized detectors to identify exactly what went wrong.

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
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
testImplementation("se.deversity.async-test-lib:async-test-lib:1.3.0")
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
| `invocations` | int | 100 | Number of invocation rounds |
| `useVirtualThreads` | boolean | true | Use Project Loom virtual threads (Java 21+) |
| `timeoutMs` | long | 5000 | Milliseconds before timeout (triggers deadlock analysis) |
| `virtualThreadStressMode` | String | `"OFF"` | `OFF` / `LOW` / `MEDIUM` / `HIGH` / `EXTREME` — pins carrier threads to increase contention |
| `detectAll` | boolean | **true** | Enable every detector at once. Set `false` to only run individually-flagged detectors |
| `excludes` | DetectorType[] | `{}` | Detectors to skip when `detectAll = true` |
| `enableBenchmarking` | boolean | false | Record timing data for regression detection |
| `benchmarkRegressionThreshold` | double | 0.2 | Regression threshold as a decimal (0.2 = 20%) |
| `failOnBenchmarkRegression` | boolean | false | Fail the test if a regression is detected |

Every individual detector flag (the full `detect*` / `validate*` / `monitor*` list further down) defaults to `true` and is gated by `detectAll`. To run a tiny subset, set `detectAll = false` and enable only what you need.

`virtualThreadStressMode` thread budgets: `LOW` ≈ 100, `MEDIUM` ≈ 1,000, `HIGH` ≈ 10,000, `EXTREME` ≈ 100,000+ (may need heap adjustment).

---

## Common patterns

### Just turn it on
```java
@AsyncTest
void testMyService() {
    myService.process(request);   // every detector active
}
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

## Tips

- **Bare `@AsyncTest` is the right starting point** — every detector is on by default in 1.x. Narrow with `excludes`, or set `detectAll = false` and turn on individual flags only after you understand the failures.
- **Increase `invocations` before `threads`** — more rounds give detectors more chances to observe bad interleavings. 200–1000 invocations is a good baseline.
- **Use `@BeforeEachInvocation` to reset shared state** between rounds; not doing so causes round N's leftover state to pollute round N+1.
- **`timeoutMs`** controls how long a round can run before deadlock analysis fires. Lower it for tests that should complete quickly.
- **`virtualThreadStressMode = "HIGH"`** is the fastest way to reproduce virtual thread pinning bugs; leave it `OFF` for normal tests (it adds overhead).
- **Detector reports follow a consistent what / why / fix layout** since 1.3.0 — read top-to-bottom for triage.
- **Benchmark baselines** are per-machine, per-environment. Commit `target/benchmark-data/` to get stable CI regression detection, or use `-Dbenchmark.store.path` to point at a stable location outside `target/`.
