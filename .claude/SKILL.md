# async-test-lib — Usage Guide

**async-test-lib** is a JUnit 5 extension for stress-testing concurrent Java code. It forces real thread collisions using a `CyclicBarrier`, then runs 146 specialized detectors across 18 phases to identify exactly what went wrong — including six JDK 25/26 detectors (Phases 16 and 18: `StableValue`, `StructuredTaskScope`, parallel `Gatherer`, `LazyConstant`, final-field mutation, shared `KDF`) you can also drive directly via AsyncTestContext accessors.

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
    <version>2.0.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
testImplementation("se.deversity.async-test-lib:async-test-lib:2.0.0")
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

> **2.0.0:** the per-detector boolean attributes (`detectRaceConditions = true` and the other 145) are gone. Select detectors by `DetectorType`: `includes = {…}` for exactly these, `excludes = {…}` to drop some, or a `preset`. A bare `@AsyncTest` still enables every detector.

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
| `detectAll` | boolean | **true** | Enable every detector at once. Honored when `preset = ALL`; ignored otherwise. `false` on its own selects nothing; name detectors with `includes` instead |
| `excludes` | DetectorType[] | `{}` | Detectors to skip — layers on top of any preset |
| `excludeIds` | String[] | `{}` | Detectors to skip by id: a third-party detector's own id, or a built-in's `DetectorType` name (2.0.0+) |
| `replaySeed` | long | 0 | **Per-round RNG seed.** `0` = fresh seed per round, printed on failure for paste-and-reproduce. Set explicitly to reproduce a failing schedule (1.6.0+) |
| `enableBenchmarking` | boolean | false | Record timing data for regression detection |
| `benchmarkRegressionThreshold` | double | 0.2 | Regression threshold as a decimal (0.2 = 20%) |
| `failOnBenchmarkRegression` | boolean | false | Fail the test if a regression is detected |

Detectors are named by `DetectorType` (the full list further down). To run a tiny subset, list it in `includes`, or use `preset = Preset.ESSENTIALS` / `Preset.CI_FAST` for curated bundles; `excludes` always removes on top.

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

### Make the workers meet mid-body: `AsyncTestContext.rendezvous()` (2.0.0+)
```java
@AsyncTest(threads = 4, invocations = 100)
void transfer() {
    Account mine = bank.open(100);       // each worker prepares its own state
    AsyncTestContext.rendezvous();       // nobody moves money until every account exists
    bank.transfer(mine, bank.randomOtherAccount(), 10);
}
```
Waits until every worker of the current round has called it, bounded by the time the round has left (`rendezvous(Duration)` for a tighter bound). Every worker must call it the same number of times; each call is one meeting point. Do not build a `CyclicBarrier` in the test for this: a worker that throws breaks the rendezvous, so its peers fail at once ("The rendezvous was broken") next to the real exception, where a hand-rolled barrier waits out the round and reports a timeout that hides it. A timeout says how many workers arrived. Outside an `@AsyncTest` worker it throws `IllegalStateException`.

### Assert a run did something exactly once: `RunOutcomes` (2.0.0+)
```java
import se.deversity.asynctest.RunOutcomes;

private static final RunOutcomes OUTCOMES = new RunOutcomes();

@AsyncTest(threads = 8, invocations = 100)
void initialise() {
    if (service.initialiseIfNeeded()) OUTCOMES.record("initialised");
    OUTCOMES.recordValue(idGenerator.next());
}

@AfterAll
static void check() {
    OUTCOMES.assertExactlyOnce("initialised");   // also: assertAtMostOnce, assertCount(event, n)
    OUTCOMES.assertDistinct();                   // no value recorded twice
}
```
Totals are per run, not per round. A failure names the count and the first threads that recorded the event. Prefer it to a static `AtomicInteger` plus a hand-written `@AfterAll`: the check is easy to write so that it holds whatever the code does (asserting a `ConcurrentHashMap`'s size, for example). Lock-free, so it adds no contention of its own.

### Check that results are linearizable: `OperationHistory` (2.0.0+, experimental)
```java
import se.deversity.asynctest.OperationHistory;
import se.deversity.asynctest.SequentialSpec;

private static final OperationHistory<AtomicInteger> HISTORY = OperationHistory.of(AtomicInteger::new);

@AsyncTest(threads = 3, invocations = 100)
void increments() {
    AtomicInteger counter = HISTORY.subject();          // one fresh object per round
    HISTORY.call("increment", null, counter::incrementAndGet);
}

@AfterAll
static void linearizable() {
    HISTORY.assertLinearizable(SequentialSpec.of(() -> new int[1], int[]::clone, (s, op, arg) -> ++s[0]));
}
```
Fails when no real-time-consistent order of a round's operations gives the results the workers saw, and lists that round's operations with their tickets. Use it when the bug's shape is unknown and no detector would recognise it. At most 64 operations per round; an undecided search and an empty history fail rather than pass.

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
    includes = {DetectorType.RACE_CONDITIONS, DetectorType.ATOMICITY_VIOLATIONS}
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
    includes = {DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, DetectorType.COMPLETABLEFUTURE_CHAIN, DetectorType.CF_COMMON_POOL_BLOCKING}
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

Select detectors below by their `DetectorType`: list them in `includes` to run only those, or in `excludes` to skip them.

### Phase 1 — Core
| DetectorType | What it catches |
|-------------|-----------------|
| `DEADLOCKS` | Thread deadlocks with lock-chain analysis (always on) |
| `VISIBILITY` | Missing `volatile`, stale memory reads |
| `LIVELOCKS` | Threads spinning without progress |

### Phase 2 — Core advanced
| DetectorType | What it catches |
|-------------|-----------------|
| `FALSE_SHARING` | Cache-line contention between threads |
| `WAKEUP_ISSUES` | A `wait()` that returned with no notify and was not waited again (`if` instead of `while`) |
| `CONSTRUCTOR_SAFETY` | Object published before fully constructed |
| `ABA_PROBLEM` | Lock-free ABA hazard |
| `LOCK_ORDER` | Inconsistent lock acquisition order |
| `SYNCHRONIZERS` | Barrier/phaser progression issues |
| `THREAD_POOL` | Executor saturation and unhealthy state |
| `MEMORY_ORDERING` | CPU reordering issues |
| `ASYNC_PIPELINE` | Event flow tracking |
| `READ_WRITE_LOCK_FAIRNESS` | Writer starvation |

### Phase 2 — Monitors
| DetectorType | What it catches |
|-------------|-----------------|
| `SEMAPHORE` | Permit leaks, over-release |
| `COMPLETABLE_FUTURE_EXCEPTIONS` | Unhandled async exceptions |
| `COMPLETABLE_FUTURE_COMPLETION_LEAKS` | Futures that are never completed |
| `VIRTUAL_THREAD_PINNING` | Virtual threads pinned to carrier thread |
| `THREAD_POOL_DEADLOCK` | Nested task submission deadlocks |
| `CONCURRENT_MODIFICATIONS` | Collection mutated during iteration |
| `LOCK_LEAKS` | Locks acquired but never released |
| `SHARED_RANDOM` | `java.util.Random` used across threads |
| `BLOCKING_QUEUE` | Queue saturation or imbalance |
| `CONDITION_VARIABLES` | Stuck waiters, wakeups no signal accounts for |
| `SIMPLE_DATE_FORMAT` | Non-thread-safe `SimpleDateFormat` |
| `PARALLEL_STREAMS` | Stateful lambdas and side effects |
| `RESOURCE_LEAKS` | `AutoCloseable` not closed |

### Phase 2 — Additional concurrency
| DetectorType | What it catches |
|-------------|-----------------|
| `COUNTDOWN_LATCH` | Timeout, missing/extra `countDown()` |
| `CYCLIC_BARRIER` | Await on a broken barrier (a recorded timeout is context, not a finding) |
| `REENTRANT_LOCK` | A lock still held at analysis by a holder that stopped working; starvation the lock saw (barging) |
| `VOLATILE_ARRAY` | Non-volatile array element access |
| `DOUBLE_CHECKED_LOCKING` | Broken DCL without `volatile` |
| `WAIT_TIMEOUT` | `wait()` without a timeout argument |
| `LOCK_CONTENTION` | Monitors with high blocked-acquire ratio |
| `SYNCHRONIZED_NON_FINAL` | `synchronized` on a non-`final` field |
| `MISSED_SIGNAL` | a wait that began after a `notify()` no thread was waiting for, and received no notify of its own |
| `LAZY_INIT_RACE` | Multi-thread lazy init without proper guards |

### Phase 2 — Advanced concurrency utilities
| DetectorType | What it catches |
|-------------|-----------------|
| `PHASER` | Arrivals after the party count reached zero, phases stalled past a timeout or behind an `arriveAndAwaitAdvance()` that never returned |
| `STAMPED_LOCK` | Unvalidated optimistic reads, stamps never released on a lock still held (conversions tracked via `recordConversion`), a read stamp released twice while another reader holds |
| `EXCHANGER` | Orphaned exchanges: a caller that started an exchange and never left it (a handled timeout is not a finding) |
| `SCHEDULED_EXECUTOR` | Missing shutdown, long-running tasks |
| `FORK_JOIN_POOL` | Fork without join |
| `THREAD_FACTORY` | Missing uncaught exception handlers |

### Phase 3 — Behavioral
| DetectorType | What it catches |
|-------------|-----------------|
| `RACE_CONDITIONS` | Unsynchronized cross-thread field access |
| `THREAD_LOCAL_LEAKS` | Missing `ThreadLocal.remove()` |
| `BUSY_WAITING` | Tight spin loops |
| `ATOMICITY_VIOLATIONS` | Check-then-act compound operations |
| `INTERRUPT_MISHANDLING` | Swallowed `InterruptedException` |

### Phase 4 — Infrastructure & resource management
| DetectorType | What it catches |
|-------------|-----------------|
| `THREAD_LEAKS` | Threads created but never terminated |
| `SLEEP_IN_LOCK` | `Thread.sleep()` while holding a lock |
| `UNBOUNDED_QUEUE` | `BlockingQueue` with no capacity bound |
| `THREAD_STARVATION` | Tasks waiting excessively for execution |

### Phase 5 — Thread-safety of common types
| DetectorType | What it catches |
|-------------|-----------------|
| `CALENDAR` | Shared `Calendar` accessed by multiple threads |
| `SHARED_COLLECTIONS` | `ArrayList`/`HashMap`/`HashSet`/etc. used concurrently without synchronization |
| `TIMER` | `java.util.Timer` failures (uncaught exception kills all tasks), tasks starved behind another on the timer thread |
| `COPY_ON_WRITE_COLLECTIONS` | `CopyOnWriteArrayList`/`Set` in write-heavy paths |
| `STRING_BUILDER` | `StringBuilder` mutated by multiple threads |

### Phase 6 — Virtual thread concurrency (Java 21+)
| DetectorType | What it catches |
|-------------|-----------------|
| `STRUCTURED_CONCURRENCY` | Unclosed `StructuredTaskScope`, missing `join()`, etc. |
| `VIRTUAL_THREAD_CONTEXT_LEAKS` | `ThreadLocal` set in virtual threads but never removed |
| `SCOPED_VALUE` | `ScopedValue.get()` outside an active binding |
| `VIRTUAL_THREAD_CPU_BOUND` | Virtual threads running long CPU-bound work without yielding |
| `VIRTUAL_THREAD_CARRIER_EXHAUSTION` | Blocked virtual threads exceeding carrier capacity |

### Phase 7 — High-level concurrency patterns
| DetectorType | What it catches |
|-------------|-----------------|
| `HTTP_CLIENT` | Unclosed responses, pool exhaustion, requests never awaited |
| `STREAM_CLOSING` | `InputStream`/`OutputStream`/`Reader`/`Writer` opened but never closed |
| `CACHE_CONCURRENCY` | `HashMap`/`LinkedHashMap` used as cache without synchronization, cache stampede |
| `COMPLETABLEFUTURE_CHAIN` | Missing `.exceptionally()`/`.handle()` in async chains |

### Phase 8 — Lifecycle & structural correctness
| DetectorType | What it catches |
|-------------|-----------------|
| `EXECUTOR_SHUTDOWN` | `ExecutorService` never shut down, or shut down without `awaitTermination()` |
| `MUTABLE_MAP_KEY` | Mutable objects used as `HashMap`/`HashSet` keys, then mutated |
| `NESTED_MONITOR_LOCKOUT` | Blocking call (`wait()`, `Future.get()`, `Lock.lock()`) while holding another monitor |
| `LOCK_DOWNGRADE` | Illegal read-to-write upgrade on `ReentrantReadWriteLock` |
| `INHERITABLE_THREAD_LOCAL` | `InheritableThreadLocal` used in pooled threads (inheritance happens at thread creation, not submission) |

### Phase 10 — API traps & subtle concurrency bugs
| DetectorType | What it catches |
|-------------|-----------------|
| `THREAD_LOCAL_CONTAMINATION` | `ThreadLocal` from one task read by the next task on the same pooled thread |
| `ATOMIC_NON_ATOMIC_UPDATE` | `get()`-then-`set()` on `AtomicInteger`/`AtomicLong` without `compareAndSet()` |
| `SYNCHRONIZED_COLLECTION_ITERATION` | Iterating `Collections.synchronizedList`/`Map`/`Set` without holding the wrapper lock |
| `SHARED_FORMATTER` | `Formatter`/`PrintWriter`/`PrintStream` shared without synchronization |
| `CONCURRENT_MAP_COMPUTE_RECURSION` | Recursive `ConcurrentHashMap.computeIfAbsent`/`compute`/`merge` on the same key |
| `SYNCHRONIZED_ON_LITERAL` | `synchronized` on interned `String` or cached `Integer`/`Long` (JVM-shared monitors) |
| `PUBLIC_LOCK_EXPOSURE` | `synchronized(this)` while `this` is publicly accessible |
| `FORK_JOIN_TASK_BLOCKING` | Blocking calls inside `ForkJoinTask` bodies — starves carrier threads |
| `OPTIMISTIC_READ_VALIDATION` | `StampedLock.tryOptimisticRead()` data used without `validate(stamp)` |
| `CF_COMMON_POOL_BLOCKING` | Blocking ops inside `CompletableFuture` stages on the common `ForkJoinPool` |

### Phase 11 — Thread-safety of additional types & patterns
| DetectorType | What it catches |
|-------------|-----------------|
| `SHARED_MATCHER` | `java.util.regex.Matcher` shared across threads (mutable match state) |
| `SHARED_DECIMAL_FORMAT` | `DecimalFormat`/`NumberFormat` shared concurrently |
| `WEAK_REFERENCE_RACE` | `WeakReference.get()` results used without null check, or referent collected mid-test |
| `STATEFUL_LAMBDA` | Lambda/`Runnable`/`Callable` capturing mutable containers (`int[]`, `Object[]`) executed concurrently |
| `SHARED_MESSAGE_DIGEST` | `MessageDigest` accessed from more than one thread — unsynchronized use corrupts hash state |

### Phase 12 — Operational & hygiene concurrency issues
| DetectorType | What it catches |
|-------------|-----------------|
| `INTERRUPT_SWALLOWING` | `catch (InterruptedException)` blocks that neither rethrow nor restore the interrupt flag |
| `MDC_CONTEXT_LEAK` | SLF4J MDC entries not cleared at task end |
| `SYSTEM_PROPERTY_MUTATION` | Concurrent `System.setProperty`/`clearProperty` calls during the run |
| `FUTURE_IGNORED` | `Future` from `submit()` never inspected — failed-task exceptions silently swallowed |
| `EXPLICIT_GC` | `System.gc()`/`Runtime.gc()` calls — corrupt timing measurements |
| `DEPRECATED_THREAD_API` | `Thread.stop()`/`suspend()`/`resume()`/`destroy()`/`countStackFrames()` |
| `SHARED_XML_PARSER` | `DocumentBuilder`/`SAXParser`/`Transformer`/`XPath` shared concurrently |
| `BOXED_PRIMITIVE_LOCK` | `synchronized` on cached `Integer`/`Long`/`Boolean.TRUE`/interned `String` |
| `SHARED_TIMEZONE` | Mutating shared `TimeZone` via `setRawOffset`/`setID` from multiple threads |
| `UNCAUGHT_EXCEPTION_HANDLER` | Threads started without a custom `UncaughtExceptionHandler` that subsequently throw |

### Phase 13 — Additional concurrency-bug categories (1.6.0+)
| DetectorType | What it catches |
|-------------|-----------------|
| `DAEMON_THREAD_HYGIENE` | Non-daemon `Thread` instances still alive at analyze time — they block JVM exit and can hang the test process. Distinct from `THREAD_LEAKS` which counts live threads regardless of daemon flag. Detector class: `DaemonThreadHygieneDetector`. |
| `NOTIFY_WITHOUT_MONITOR` | `notify()`/`notifyAll()` attempts (declared via `recordNotifyAttempt`) when the calling thread does not hold the monitor — would throw `IllegalMonitorStateException` at runtime and leave `wait()`-ers blocked. Complements `MISSED_SIGNAL` (which catches notifies with no waiter). |
| `SHARED_SECURE_RANDOM` | `java.security.SecureRandom` instances accessed from multiple threads. Thread safety is provider-dependent (SHA1PRNG, NativePRNG, Bouncy Castle, custom SPIs all differ). Distinct from `SHARED_RANDOM` which covers `java.util.Random` only. Report carries algorithm + provider names. |
| `WEAK_HASH_MAP_SHARED` | `WeakHashMap` or `IdentityHashMap` instances accessed from multiple threads. Both have additional concurrency hazards beyond regular `HashMap`: GC-driven entry removal mutates the table on every get/put; linear probing can drop or duplicate entries under concurrent puts. |
| `JDBC_CONNECTION_SHARED` | `java.sql.Connection`/`Statement`/`PreparedStatement`/`ResultSet` accessed from multiple threads. JDBC spec does not require any of these to be thread-safe; most drivers (Postgres/MySQL/Oracle) document one-thread-per-Connection. Concurrent use produces mixed cursors, protocol corruption, or transaction leakage. |

### Phase 14 — Additional thread-unsafe primitives & publication hazards (1.7.0+)
| DetectorType | What it catches |
|-------------|-----------------|
| `SHARED_STATEFUL_CRYPTO` | `javax.crypto.Cipher`, `javax.crypto.Mac`, and `java.security.Signature` instances accessed from multiple threads. Unlike `MessageDigest`, these carry mutable per-operation state across `init → update → doFinal`/`sign`/`verify`; interleaved calls corrupt ciphertext or fold bytes from two callers into one MAC/signature that verifies for neither. Instrument via `AsyncTestContext.sharedStatefulCryptoDetector().recordAccess(cipher/mac/signature, name, thread)`. Sibling of `SHARED_MESSAGE_DIGEST` / `SHARED_SECURE_RANDOM`. |
| `CONCURRENT_MAP_CHECK_THEN_ACT` | Non-atomic check-then-act on a `ConcurrentMap` (`containsKey`/`get` then `put`) performed by multiple threads against the same map+key — a lost-update race. Each op is atomic but the compound sequence is not; use `putIfAbsent`/`computeIfAbsent`/`compute`/`merge`. Instrument via `recordCheckThenAct(map, key, operation, thread)`. Distinct from `ATOMIC_NON_ATOMIC_UPDATE` (`Atomic*` types) and `CONCURRENT_MAP_COMPUTE_RECURSION` (re-entrancy inside `computeIfAbsent`). |
| `SHARED_DEFLATER` | `java.util.zip.Deflater`/`Inflater` accessed from multiple threads. Both wrap a stateful native zlib stream and are not thread-safe; concurrent use corrupts output or crashes when one thread calls `end()` mid-stream. Instrument via `recordAccess(deflater/inflater, name, thread)`. |
| `THIS_ESCAPE` | A constructor that publishes `this` before returning (starts a thread, registers a listener, stores into shared state), exposing a partially-constructed object — no final-field visibility guarantee, fields may still be default. Instrument via `recordConstructorEscape(this, how, thread)`, plus optional `recordExternalAccess(instance, thread)` / `recordConstructionComplete(instance)`. MEDIUM, escalated to HIGH when another thread observes it before completion. |
| `THREAD_LOCAL_RANDOM_MISUSE` | A `ThreadLocalRandom.current()` reference cached (e.g. in a field) and used from a thread other than the one that obtained it, defeating its per-thread isolation. Instrument via `recordObtain(rng, name, thread)` then `recordUse(rng, thread)`. Distinct from `SHARED_RANDOM` (`java.util.Random`) and `SHARED_SECURE_RANDOM`. |

### Phase 17 — Shared stateful JDK objects, I/O position races & contention advisories (1.7.0+)
| DetectorType | What it catches |
|-------------|-----------------|
| `SHARED_BYTE_BUFFER` | A `ByteBuffer` (position/limit/mark are mutable state) accessed from multiple threads |
| `SHARED_CHARSET_CODER` | `CharsetEncoder`/`CharsetDecoder` shared across threads — internal coding state garbles output |
| `SHARED_CHECKSUM` | `CRC32`/`CRC32C`/`Adler32` shared across threads — silently wrong checksums |
| `FILE_CHANNEL_POSITION_RACE` | `FileChannel` `position(n)` then `read`/`write` relying on it, with another thread's call able to land between them |
| `SHARED_ITERATOR` | An `Iterator`/`ListIterator`/`Spliterator` consumed by more than one thread |
| `HIGH_CONTENTION_ATOMIC` | CAS retry storms on hot `Atomic*` fields — advisory to switch to `LongAdder` |
| `SHARED_JSON_MAPPER_RECONFIG` | Mapper (`ObjectMapper`, `Gson`) reconfigured while another thread uses it in the same round |

### JDK 25/26 detectors — Phases 16 & 18 (1.7.0+ / 1.8.0+)

These six detectors target concurrency features introduced/finalized in JDK 24–26. They
are **wired into the `@AsyncTest` pipeline** — each has a `DetectorType` constant and an
`AsyncTestContext` accessor — and can also be
instantiated standalone. They live in `se.deversity.asynctest.diagnostics` and are
thread-safe (`ConcurrentHashMap`-backed), so a single instance can be shared across
the worker threads of an `@AsyncTest`.

| Detector | JDK feature | Record API | What it catches |
|----------|-------------|------------|-----------------|
| `StableValueMisuseDetector` | `StableValue` — JEP 502 (preview JDK 25; renamed to Lazy Constants in JDK 26) | `recordSet(name, thread)`, `recordRead(name, thread)`, `recordSupplierStart/End(name, thread)` | read-before-set (`NoSuchElementException`), double-set (lost update / `IllegalStateException`), reentrant `orElseSet` supplier, set-contention |
| `StructuredTaskScopeMisuseDetector` | `StructuredTaskScope` — JEP 505/525 (fifth preview JDK 25, sixth preview JDK 26) | `recordScopeOpened(id, owner)`, `recordFork(id, subtaskId, thread)`, `recordJoin(id, thread)`, `recordJoinTimeout(id, thread)` (1.8.0+), `recordTimeoutSwallowed(id, thread)` (1.8.0+), `recordResultRead(id, subtaskId, thread)`, `recordScopeClosed(id, thread)` | fork-after-join, `Subtask.get()` before join, owner-confinement (`WrongThreadException`), close-without-join, `Subtask.get()` after a join timeout (JDK 26 `Joiner.onTimeout()`), timeout-swallowing fallback with cancelled subtasks |
| `GathererConcurrencyMisuseDetector` | Stream Gatherers — JEP 485 (final JDK 24) | `registerGatherer(name, hasCombiner, parallel)`, `recordIntegrate(name, thread)` | one state shared across parallel segments (race); no combiner on a parallel stream (LOW: the stage runs sequentially, nothing is lost) |
| `LazyConstantMisuseDetector` (1.8.0+) | `LazyConstant` — Lazy Constants, second preview JDK 26 (successor of `StableValue`) | `recordGet(name, thread)`, `recordComputeStart(name, thread)`, `recordComputeEnd(name, thread, result)` | reentrant supplier (`IllegalStateException`), null-producing supplier (NPE on JDK 26), computation running more than once (hand-rolled holder), non-deterministic supplier, compute convoy |
| `FinalFieldMutationDetector` (1.8.0+) | JEP 500 — final-field mutation warnings, JDK 26 | `recordMutation(field, thread)`, `recordRead(field, thread)` | any reflective `final`-field write (HIGH — denied in a future JDK, JMM violation today); escalates to CRITICAL when foreign threads read the field or multiple threads write it |
| `SharedKdfDetector` (1.8.0+) | `javax.crypto.KDF` — JEP 510, final JDK 25 | `recordAccess(kdf, algorithm, operation, thread)` | one KDF instance accessed from multiple threads — documented not thread-safe, silently derives wrong keys |

```java
@AsyncTest(threads = 16, invocations = 50)
void lazyConfig() {
    var detector = AsyncTestContext.lazyConstantMisuseDetector();
    detector.recordGet("CONFIG", Thread.currentThread());
    detector.recordComputeStart("CONFIG", Thread.currentThread());
    Config c = loadConfig();                        // the supplier body
    detector.recordComputeEnd("CONFIG", Thread.currentThread(), c);
}
```

Each `analyze()` returns a typed `*Report` with `hasIssues()`, per-category issue
lists, and a `toString()` that includes a `📚 LEARNING` block — same shape as every
other detector report.

> **Virtual thread pinning is JDK-version-aware since 1.8.0**: `synchronized`/`Object.wait`
> pinning events are annotated as no-longer-pinning on JDK 24+ (JEP 491) and class-init
> waits on JDK 26+; blocking native (JNI/FFM) calls always pin. Use
> `PinningReport.hasEffectivePinningIssues()` to ignore obsolete causes.

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
    public void onViolation(Violation violation) {   // 1.9.0+
        metrics.count(violation.detector(), violation.severity());
    }

    @Override
    public void onTimeout(long timeoutMs) { }
}

// Register once, e.g., in @BeforeAll or a static initializer
AsyncTestListenerRegistry.register(new MyListener());
```

Listeners may be called from multiple threads concurrently — implementations must be thread-safe.

---

## Asserting on detector findings (1.9.0+)

`AsyncFindings` records the structured `Violation` behind every finding, so a test can assert that a detector fired instead of substring-matching a report written for humans:

```java
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.FailOn;

class CounterTest {

    static AsyncFindings findings;

    @BeforeAll static void collect() { findings = AsyncFindings.collect(); }
    @AfterAll  static void release() { findings.close(); }   // its own method: runs even if the assertions below fail

    @AsyncTest(threads = 4, invocations = 50, failOn = FailOn.NONE)
    void increments() {
        counter.increment();
    }

    @AfterAll
    static void theRaceIsReported() {
        findings.assertReported("RaceConditionDetector");
        findings.assertNotReported("DeadlockDetector");
    }
}
```

| Call | Asserts |
|------|---------|
| `assertReported(name)` | that detector reported at least one finding |
| `assertReported(name, severity)` | that it reported one at that `IssueSeverity` |
| `assertNotReported(name)` | that it reported nothing |
| `assertNone()` | that no detector reported anything |
| `violations()` / `violationsFrom(name)` | the `Violation` records, for anything else |

Three rules make it work:

- **`failOn = FailOn.NONE`** — at the default threshold the run fails before the assertion is reached.
- **`@BeforeAll` / `@AfterAll`** — detectors analyse after the last round, so a finding cannot be observed from inside the test body, and JUnit does not order `@Test` against `@AsyncTest` methods.
- **`close()` in its own `@AfterAll`** — the listener registry is JVM-wide; an unclosed collector keeps recording findings from every later test in the same JVM. Closing in the same method as the assertions leaks it whenever one of them fails, which is exactly when they exist to fail. `clear()` resets one between tests.

Names are detector simple class names (`RaceConditionDetector`), matched case-insensitively by substring; a null or blank name is rejected with `IllegalArgumentException` rather than matching nothing, so a typo cannot turn `assertNotReported` into an assertion that always passes. A failed assertion lists what was reported instead. The same records reach any listener via `onViolation(Violation)`; the full report text stays under the `"report"` attribute.

`AsyncAssert` gained matching ergonomics: `awaitUntil(condition, timeout, "queue drained")` names the wait and reports the last exception the condition threw, and `FutureCapture.requireResult()` / `isSuccess()` / `isFailed()` separate "still running", "failed" and "completed with null", which `getResult() == null` conflated.

Runnable example: [examples/135-asserting-on-findings](../examples/135-asserting-on-findings/).

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
    @Override public String id() { return "com.acme.my-detector"; }   // its own identity (2.0.0+)
    @Override public Detector create(AsyncTestConfig cfg) { return new MyDetector(); }
    // isEnabledFor defaults to cfg.isEnabled(id()): on unless @AsyncTest(excludeIds = "com.acme.my-detector")
}
```

`MyDetector` overrides `id()` the same way. A detector that stands for a built-in overrides
`type()` instead, and its id is the `DetectorType` name.

```java
DetectorRegistry reg = DetectorRegistry.buildExternal(config);   // the detectors you added
List<Violation> all = reg.analyzeAll();
MyDetector mine = reg.get(MyDetector.class);
Detector byId = reg.get("com.acme.my-detector");
```

The built-in detectors are not in the SPI registry: since 2.0.0 they have one registry, the runner's, and `DetectorRegistry.build(config)` (which added a blind bridge copy of each) is gone. The runner builds `buildExternal(config)` for every `@AsyncTest`, so a registered factory's findings reach the reports and the `failOn` gate without further wiring.

---

## Tips

- **Bare `@AsyncTest` is the right starting point** — every detector is on by default. Narrow with `excludes`, or name the detectors you want with `includes` once you understand the failures.
- **Increase `invocations` before `threads`** — more rounds give detectors more chances to observe bad interleavings. 200–1000 invocations is a good baseline.
- **Use `@BeforeEachInvocation` to reset shared state** between rounds; not doing so causes round N's leftover state to pollute round N+1.
- **`timeoutMs`** controls how long a round can run before deadlock analysis fires. Lower it for tests that should complete quickly.
- **`virtualThreadStressMode = "HIGH"`** is the fastest way to reproduce virtual thread pinning bugs; leave it `OFF` for normal tests (it adds overhead).
- **Detector reports follow a consistent what / why / fix layout** since 1.3.0 — read top-to-bottom for triage.
- **Benchmark baselines** are per-machine, per-environment. Commit `target/benchmark-data/` to get stable CI regression detection, or use `-Dbenchmark.store.path` to point at a stable location outside `target/`.
