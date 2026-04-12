# async-test-lib — Usage Guide

**async-test-lib** is a JUnit 5 extension for stress-testing concurrent Java code. It forces real thread collisions using a `CyclicBarrier`, then runs 51+ specialized detectors to identify exactly what went wrong.

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
    <version>0.5.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
testImplementation("se.deversity.async-test-lib:async-test-lib:0.5.0")
```

---

## Quickstart

```java
import com.github.asynctest.AsyncTest;

class CounterTest {

    private int counter = 0; // BUG: not thread-safe

    @AsyncTest(threads = 10, invocations = 100, detectAll = true)
    void increment() {
        counter++;            // Caught: race condition / atomicity violation
    }
}
```

`@AsyncTest` launches `threads` concurrent threads per invocation, repeats `invocations` times, and reports exactly which detector fired and why. The same test with plain `@Test` would pass silently.

---

## @AsyncTest — all parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 10 | Concurrent threads per invocation round |
| `invocations` | int | 100 | Number of invocation rounds |
| `useVirtualThreads` | boolean | true | Use Project Loom virtual threads (Java 21+) |
| `timeoutMs` | long | 5000 | Milliseconds before timeout (triggers deadlock analysis) |
| `virtualThreadStressMode` | String | `"OFF"` | `OFF` / `LOW` / `MEDIUM` / `HIGH` / `EXTREME` — pins carrier threads to increase contention |
| `detectAll` | boolean | false | Enable every detector at once |
| `excludes` | DetectorType[] | `{}` | Detectors to skip when `detectAll = true` |
| `detectDeadlocks` | boolean | true | Always-on deadlock detection |
| `detectVisibility` | boolean | false | Missing `volatile`, stale memory reads |
| `detectLivelocks` | boolean | false | Threads spinning without making progress |
| `detectRaceConditions` | boolean | false | Unsynchronized cross-thread field access |
| `detectAtomicityViolations` | boolean | false | Check-then-act patterns (e.g., `if (!map.containsKey(k)) map.put(k, v)`) |
| `detectBusyWaiting` | boolean | false | Tight spin loops |
| `detectThreadLocalLeaks` | boolean | false | Missing `ThreadLocal.remove()` |
| `detectInterruptMishandling` | boolean | false | Swallowed `InterruptedException` |
| `enableBenchmarking` | boolean | false | Record timing data for regression detection |
| `benchmarkRegressionThreshold` | double | 0.2 | Regression threshold as a decimal (0.2 = 20%) |
| `failOnBenchmarkRegression` | boolean | false | Fail the test if a regression is detected |

*Individual Phase 2 detectors (false sharing, ABA, lock order, CompletableFuture, etc.) can each be turned on independently — see the full list below.*

---

## Common patterns

### Race condition — simplest case
```java
@AsyncTest(threads = 20, invocations = 50, detectRaceConditions = true)
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

### Enable everything, opt out of noisy detectors
```java
import com.github.asynctest.DetectorType;

@AsyncTest(
    threads = 16,
    invocations = 200,
    detectAll = true,
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
    virtualThreadStressMode = "HIGH",
    detectVirtualThreadPinning = true
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
    detectCompletableFutureExceptions = true,
    detectCompletableFutureCompletionLeaks = true
)
void testAsyncPipeline() {
    CompletableFuture<Result> future = service.processAsync(item);
    // Unhandled exceptions and abandoned futures are caught
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

### Phase 1 — Core
| Annotation field | DetectorType | Default | What it catches |
|-----------------|-------------|---------|-----------------|
| `detectDeadlocks` | `DEADLOCKS` | **true** | Thread deadlocks with lock-chain analysis |
| `detectVisibility` | `VISIBILITY` | false | Missing `volatile`, stale memory reads |
| `detectLivelocks` | `LIVELOCKS` | false | Threads spinning without progress |

### Phase 2 — Advanced (all default false; all enabled by `detectAll`)
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
| `detectCountDownLatchIssues` | `COUNTDOWN_LATCH` | Timeout, missing/extra `countDown()` |
| `detectCyclicBarrierIssues` | `CYCLIC_BARRIER` | Timeout, broken barriers |
| `detectReentrantLockIssues` | `REENTRANT_LOCK` | Starvation, unfair acquisition |
| `detectVolatileArrayIssues` | `VOLATILE_ARRAY` | Non-volatile array element access |
| `detectDoubleCheckedLocking` | `DOUBLE_CHECKED_LOCKING` | Broken DCL without `volatile` |
| `detectWaitTimeout` | `WAIT_TIMEOUT` | `wait()` without a timeout argument |
| `detectPhaserIssues` | `PHASER` | Missing `arrive()`, timeouts |
| `detectStampedLockIssues` | `STAMPED_LOCK` | Unvalidated optimistic reads |
| `detectExchangerIssues` | `EXCHANGER` | Timeouts, missing exchange partners |
| `detectScheduledExecutorIssues` | `SCHEDULED_EXECUTOR` | Missing shutdown, long-running tasks |
| `detectForkJoinPoolIssues` | `FORK_JOIN_POOL` | Fork without join |
| `detectThreadFactoryIssues` | `THREAD_FACTORY` | Missing uncaught exception handlers |

### Phase 3 — Behavioral
| Annotation field | DetectorType | Default | What it catches |
|-----------------|-------------|---------|-----------------|
| `detectRaceConditions` | `RACE_CONDITIONS` | false | Unsynchronized cross-thread field access |
| `detectThreadLocalLeaks` | `THREAD_LOCAL_LEAKS` | false | Missing `ThreadLocal.remove()` |
| `detectBusyWaiting` | `BUSY_WAITING` | false | Tight spin loops |
| `detectAtomicityViolations` | `ATOMICITY_VIOLATIONS` | false | Check-then-act compound operations |
| `detectInterruptMishandling` | `INTERRUPT_MISHANDLING` | false | Swallowed `InterruptedException` |

### Phase 4 — Infrastructure
| Annotation field | DetectorType | Default | What it catches |
|-----------------|-------------|---------|-----------------|
| `detectThreadLeaks` | `THREAD_LEAKS` | false | Threads created but never terminated |
| `detectSleepInLock` | `SLEEP_IN_LOCK` | false | `Thread.sleep()` while holding a lock |
| `detectUnboundedQueue` | `UNBOUNDED_QUEUE` | false | `BlockingQueue` with no capacity bound |
| `detectThreadStarvation` | `THREAD_STARVATION` | false | Tasks waiting excessively for execution |

---

## Observability — AsyncTestListener

Register a listener to receive events from every test run:

```java
import com.github.asynctest.AsyncTestListener;
import com.github.asynctest.AsyncTestListenerRegistry;

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

- **Start with `detectAll = true`** to catch everything, then narrow to only the detectors you care about once you understand the failures.
- **Increase `invocations` before `threads`** — more rounds give detectors more chances to observe bad interleavings. 200–1000 invocations is a good baseline.
- **Use `@BeforeEachInvocation` to reset shared state** between rounds; not doing so causes round N's leftover state to pollute round N+1.
- **`timeoutMs`** controls how long a round can run before deadlock analysis fires. Lower it for tests that should complete quickly.
- **`virtualThreadStressMode = "HIGH"`** is the fastest way to reproduce virtual thread pinning bugs; leave it `OFF` for normal tests (it adds overhead).
- **Benchmark baselines** are per-machine, per-environment. Commit `target/benchmark-data/` to get stable CI regression detection, or use `-Dbenchmark.store.path` to point at a stable location outside `target/`.
