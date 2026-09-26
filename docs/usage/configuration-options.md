# Configuration options

Part of the [Usage guide](../USAGE.md).

## Configuration Options

### Core Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 10 | Number of threads to spawn |
| `invocations` | int | 100 | Number of times the concurrent round runs |
| `timeoutMs` | long | 5000 | Test timeout in milliseconds |
| `useVirtualThreads` | boolean | true | Use Java 21+ virtual threads |
| `virtualThreadStressMode` | String | "OFF" | Virtual thread stress level (OFF, LOW, MEDIUM, HIGH, EXTREME) |
| `detectAll` | boolean | true | **Enable ALL detectors in one shot (Recommended)**. An individual flag set to `false` does not opt out while this is `true`; use `excludes` |
| `excludes` | DetectorType[] | {} | Detectors to skip when `detectAll = true` |

### Phase 1 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectDeadlocks` | boolean | true | Detect circular lock dependencies |
| `detectVisibility` | boolean | false | Detect missing volatile keywords |
| `detectLivelocks` | boolean | false | Detect thread spinning and starvation |

### Phase 2 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectFalseSharing` | boolean | false | Detect cache line contention |
| `detectWakeupIssues` | boolean | false | Detect spurious/lost wakeups |
| `validateConstructorSafety` | boolean | false | Detect unsafe object publication |
| `detectABAProblem` | boolean | false | Detect ABA problems in lock-free code |
| `validateLockOrder` | boolean | false | Detect improper lock acquisition order |
| `monitorSynchronizers` | boolean | false | Detect problems in synchronizers |
| `monitorThreadPool` | boolean | false | Monitor thread pool behavior |
| `detectMemoryOrderingViolations` | boolean | false | Detect JMM happens-before violations |
| `monitorAsyncPipeline` | boolean | false | Monitor event flow through async pipelines |
| `monitorReadWriteLockFairness` | boolean | false | Detect writer starvation and unfair locks |

### Phase 3 Detectors (Enabled by default if detectAll=true)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectRaceConditions` | boolean | false | Track concurrent field access patterns |
| `detectThreadLocalLeaks` | boolean | false | Detect ThreadLocal values not cleaned up |
| `detectBusyWaiting` | boolean | false | Detect spin loops and tight polling |
| `detectAtomicityViolations` | boolean | false | Detect non-atomic compound operations |
| `detectInterruptMishandling` | boolean | false | Detect swallowed interrupts and missing restoration |

### Phase 5 Detectors — Thread-Safety of Common Types (Enabled by default if detectAll=true)

These detectors catch misuse of common Java standard-library types that are **not thread-safe**
but are frequently shared across threads by mistake.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectCalendarIssues` | boolean | true | Detect `java.util.Calendar` shared across threads (not thread-safe; use `java.time.*`) |
| `detectSharedCollections` | boolean | true | Detect `ArrayList`/`HashMap`/`HashSet` etc. written by multiple threads without synchronization |
| `detectTimerIssues` | boolean | true | Detect `java.util.Timer` thread failures (uncaught exception kills all tasks) and tasks that fell due while another task held the timer thread |
| `detectCopyOnWriteCollectionIssues` | boolean | true | Detect `CopyOnWriteArrayList`/`CopyOnWriteArraySet` with high write ratio (O(n) copy per write) |
| `detectStringBuilderIssues` | boolean | true | Detect `StringBuilder` mutated by multiple threads (not thread-safe; use `StringBuffer` or `ThreadLocal`) |

#### Context accessors for Phase 5 detectors

```java
AsyncTestContext.calendarMonitor()           // CalendarDetector
AsyncTestContext.sharedCollectionMonitor()   // SharedCollectionDetector
AsyncTestContext.timerMonitor()              // TimerDetector
AsyncTestContext.copyOnWriteMonitor()        // CopyOnWriteCollectionDetector
AsyncTestContext.stringBuilderMonitor()      // StringBuilderDetector
```

#### CalendarDetector example

```java
@AsyncTest(threads = 4, detectCalendarIssues = true)
void testCalendarSharing() {
    Calendar cal = Calendar.getInstance();
    AsyncTestContext.calendarMonitor()
        .registerCalendar(cal, "shared-calendar");

    cal.set(Calendar.YEAR, 2024);
    AsyncTestContext.calendarMonitor()
        .recordSet(cal, "shared-calendar");
}
// Fix: use LocalDate/ZonedDateTime from java.time.* (immutable, thread-safe)
```

#### SharedCollectionDetector example

```java
@AsyncTest(threads = 4, detectSharedCollections = true)
void testSharedList() {
    List<String> list = new ArrayList<>();   // BUG: not thread-safe
    AsyncTestContext.sharedCollectionMonitor()
        .registerCollection(list, "item-list", "ArrayList");

    list.add("item");
    AsyncTestContext.sharedCollectionMonitor()
        .recordWrite(list, "item-list", "add");
}
// Fix: use ConcurrentHashMap, CopyOnWriteArrayList, or Collections.synchronizedList()
```

#### TimerDetector example

```java
@AsyncTest(threads = 2, detectTimerIssues = true)
void testTimerUsage() {
    Timer timer = new Timer("my-timer");
    TimerDetector detector = AsyncTestContext.timerMonitor();
    detector.registerTimer(timer, "my-timer");

    timer.schedule(new TimerTask() {
        public void run() {
            // Pass the task itself: its scheduledExecutionTime() says when it fell due, and a task
            // that fell due while another held the timer's one thread is reported as starved.
            detector.recordTaskRun(timer, "my-timer", this, "task-1");
            doWork();
            detector.recordTaskComplete(timer, "my-timer", "task-1");
        }
    }, 0);
}
// Fix: replace java.util.Timer with ScheduledExecutorService
```

#### CopyOnWriteCollectionDetector example

```java
@AsyncTest(threads = 4, detectCopyOnWriteCollectionIssues = true)
void testWriteHeavyCopyOnWrite() {
    CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
    AsyncTestContext.copyOnWriteMonitor()
        .registerCollection(list, "event-list");

    list.add("event");
    AsyncTestContext.copyOnWriteMonitor()
        .recordWrite(list, "event-list");
}
// Fix: use ConcurrentHashMap.newKeySet() or ConcurrentLinkedQueue for write-heavy workloads
```

#### StringBuilderDetector example

```java
@AsyncTest(threads = 4, detectStringBuilderIssues = true)
void testSharedStringBuilder() {
    StringBuilder sb = new StringBuilder();   // BUG: not thread-safe
    AsyncTestContext.stringBuilderMonitor()
        .registerBuilder(sb, "log-builder");

    sb.append("entry");
    AsyncTestContext.stringBuilderMonitor()
        .recordAppend(sb, "log-builder");
}
// Fix: use ThreadLocal<StringBuilder> or build strings per-thread and join at the end
```

### Phase 8: Lifecycle & Structural Correctness (v1.6.0)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectExecutorShutdown` | boolean | true | Detect `ExecutorService` tasks submitted but never shut down, or shut down without `awaitTermination()` |
| `detectMutableMapKeys` | boolean | true | Detect `HashMap`/`HashSet` keys mutated after insertion, silently breaking future lookups |
| `detectNestedMonitorLockout` | boolean | true | Detect blocking ops (`wait`/`Future.get`/`lock`) attempted while holding a different monitor |
| `detectLockDowngrade` | boolean | true | Detect illegal read-to-write upgrade on `ReentrantReadWriteLock` (deadlocks immediately) |
| `detectInheritableThreadLocalMisuse` | boolean | true | Detect `InheritableThreadLocal` accessed from pooled threads (value frozen at thread-creation time, not task-submission time) |

#### Context accessors for Phase 8 detectors

```java
AsyncTestContext.executorShutdownMonitor()             // ExecutorShutdownDetector
AsyncTestContext.mutableMapKeyMonitor()                // MutableMapKeyDetector
AsyncTestContext.nestedMonitorLockoutMonitor()         // NestedMonitorLockoutDetector
AsyncTestContext.lockDowngradeMonitor()                // LockDowngradeDetector
AsyncTestContext.inheritableThreadLocalMisuseMonitor() // InheritableThreadLocalMisuseDetector
```

### Phase 10: API Traps & Subtle Concurrency Bugs (v1.6.0)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectThreadLocalContamination` | boolean | true | Detect `ThreadLocal` set in task A read by task B on the same reused pooled thread |
| `detectAtomicNonAtomicUpdates` | boolean | true | Detect `get()` + `set()` on `Atomic*` without `compareAndSet()`, losing concurrent updates |
| `detectSynchronizedCollectionIteration` | boolean | true | Detect `Collections.synchronized*` iterated without holding the wrapper lock |
| `detectSharedFormatter` | boolean | true | Detect `Formatter`/`PrintWriter`/`PrintStream` accessed from multiple threads concurrently |
| `detectConcurrentMapComputeRecursion` | boolean | true | Detect a `compute*`/`merge` mapping function that re-enters its own map on the same thread, on the same key (the nested update is discarded) or on any other key (usually returns normally, leaving the map updated out of order). Nesting into a different map is not reported |
| `detectSynchronizedOnLiteral` | boolean | true | Detect `synchronized` on interned `String` or cached `Integer`/`Long` [-128, 127] — JVM-wide shared monitor |
| `detectPublicLockExposure` | boolean | true | Detect `synchronized(this)` on publicly accessible objects — enables external lock acquisition |
| `detectForkJoinTaskBlocking` | boolean | true | Detect blocking calls (`sleep`/`wait`/`get`/IO) inside a `ForkJoinTask`, starving carrier threads |
| `detectOptimisticReadValidation` | boolean | true | Detect `StampedLock` optimistic-read data used without `validate(stamp)` or after failed validation |
| `detectCFCommonPoolBlocking` | boolean | true | Detect blocking work inside `CompletableFuture` submitted without a custom `Executor` |

#### Context accessors for Phase 10 detectors

```java
AsyncTestContext.threadLocalContaminationMonitor()         // ThreadLocalContaminationDetector
AsyncTestContext.atomicNonAtomicUpdateMonitor()            // AtomicNonAtomicUpdateDetector
AsyncTestContext.synchronizedCollectionIterationMonitor()  // SynchronizedCollectionIterationDetector
AsyncTestContext.sharedFormatterMonitor()                  // SharedFormatterDetector
AsyncTestContext.concurrentMapComputeRecursionMonitor()    // ConcurrentMapComputeRecursionDetector
AsyncTestContext.synchronizedOnLiteralMonitor()            // SynchronizedOnLiteralDetector
AsyncTestContext.publicLockExposureMonitor()               // PublicLockExposureDetector
AsyncTestContext.forkJoinTaskBlockingMonitor()             // ForkJoinTaskBlockingDetector
AsyncTestContext.optimisticReadValidationMonitor()         // OptimisticReadValidationDetector
AsyncTestContext.cfCommonPoolBlockingMonitor()             // CompletableFutureCommonPoolBlockingDetector
```

### Phase 12: Operational & Hygiene Concurrency Issues (v0.10.0)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `detectInterruptSwallowing` | boolean | true | Detect `catch(InterruptedException)` blocks that swallow the signal without calling `Thread.currentThread().interrupt()` or rethrowing |
| `detectMdcContextLeak` | boolean | true | Detect SLF4J MDC entries not cleared at task end, leaking into the next task on a reused pooled thread |
| `detectSystemPropertyMutation` | boolean | true | Detect concurrent `System.setProperty()` / `clearProperty()` calls causing non-deterministic configuration |
| `detectFutureIgnored` | boolean | true | Detect `Future`s from `submit()` that are never inspected — exceptions from failed tasks are silently swallowed |
| `detectExplicitGc` | boolean | true | Detect `System.gc()` / `Runtime.gc()` invocations that trigger unpredictable STW pauses mid-test |
| `detectDeprecatedThreadApi` | boolean | true | Detect calls to `Thread.stop()`, `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`, `Thread.countStackFrames()` |
| `detectSharedXmlParser` | boolean | true | Detect `DocumentBuilder` / `SAXParser` / `Transformer` / `XPath` instances accessed from multiple threads |
| `detectBoxedPrimitiveLock` | boolean | true | Detect `synchronized` on cached `Integer`/`Long` (−128..127), `Boolean.TRUE/FALSE`, or interned `String` literals |
| `detectSharedTimeZone` | boolean | true | Detect `TimeZone` instances mutated (`setRawOffset`, `setID`) from multiple threads |
| `detectUncaughtExceptionHandler` | boolean | true | Detect threads started without a custom `UncaughtExceptionHandler`, and with no JVM-wide default handler, that subsequently throw |

#### Context accessors for Phase 12 detectors

```java
AsyncTestContext.interruptSwallowingDetector()        // InterruptSwallowingDetector
AsyncTestContext.mdcContextLeakDetector()             // MdcContextLeakDetector
AsyncTestContext.systemPropertyMutationDetector()     // SystemPropertyMutationDetector
AsyncTestContext.futureIgnoredDetector()              // FutureIgnoredDetector
AsyncTestContext.explicitGcDetector()                 // ExplicitGcDetector
AsyncTestContext.deprecatedThreadApiDetector()        // DeprecatedThreadApiDetector
AsyncTestContext.sharedXmlParserDetector()            // SharedXmlParserDetector
AsyncTestContext.boxedPrimitiveLockDetector()         // BoxedPrimitiveLockDetector
AsyncTestContext.sharedTimeZoneDetector()             // SharedTimeZoneDetector
AsyncTestContext.uncaughtExceptionHandlerDetector()   // UncaughtExceptionHandlerDetector
```
