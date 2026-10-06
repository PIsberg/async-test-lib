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
| `preset` | Preset | ESSENTIALS | Curated detector bundle, used when neither `includes` nor `detectAll = true` is set. A bare `@AsyncTest` runs `ESSENTIALS` (2.0.0) |
| `detectAll` | boolean | false | Enable every detector, whatever `preset` says; `includes` still wins. `false` leaves `preset` in charge |
| `includes` | DetectorType[] | {} | Enable exactly these detectors; overrides `preset` and `detectAll` when non-empty |
| `excludes` | DetectorType[] | {} | Detectors to skip from whatever `includes`, `preset` or `detectAll` selected |
| `excludeIds` | String[] | {} | Detectors to skip by id: a third-party detector's own `id()`, or a built-in's `DetectorType` name (2.0.0+) |

### Phase 1 Detectors (Enabled by detectAll = true)

| DetectorType | Description |
|--------------|-------------|
| `DEADLOCKS` | Detect circular lock dependencies |
| `VISIBILITY` | Detect missing volatile keywords |
| `LIVELOCKS` | Detect thread spinning and starvation |

### Phase 2 Detectors (Enabled by detectAll = true)

| DetectorType | Description |
|--------------|-------------|
| `FALSE_SHARING` | Detect cache line contention |
| `WAKEUP_ISSUES` | Detect spurious/lost wakeups |
| `CONSTRUCTOR_SAFETY` | Detect unsafe object publication |
| `ABA_PROBLEM` | Detect ABA problems in lock-free code |
| `LOCK_ORDER` | Detect improper lock acquisition order |
| `SYNCHRONIZERS` | Detect problems in synchronizers |
| `THREAD_POOL` | Monitor thread pool behavior |
| `MEMORY_ORDERING` | Detect JMM happens-before violations |
| `ASYNC_PIPELINE` | Monitor event flow through async pipelines |
| `READ_WRITE_LOCK_FAIRNESS` | Detect writer starvation and unfair locks |

### Phase 3 Detectors (Enabled by detectAll = true)

| DetectorType | Description |
|--------------|-------------|
| `RACE_CONDITIONS` | Track concurrent field access patterns |
| `THREAD_LOCAL_LEAKS` | Detect ThreadLocal values not cleaned up |
| `BUSY_WAITING` | Detect spin loops and tight polling |
| `ATOMICITY_VIOLATIONS` | Detect non-atomic compound operations |
| `INTERRUPT_MISHANDLING` | Detect swallowed interrupts and missing restoration |

### Phase 5 Detectors — Thread-Safety of Common Types (Enabled by detectAll = true)

These detectors catch misuse of common Java standard-library types that are **not thread-safe**
but are frequently shared across threads by mistake.

| DetectorType | Description |
|--------------|-------------|
| `CALENDAR` | Detect `java.util.Calendar` shared across threads (not thread-safe; use `java.time.*`) |
| `SHARED_COLLECTIONS` | Detect `ArrayList`/`HashMap`/`HashSet` etc. written by multiple threads without synchronization |
| `TIMER` | Detect `java.util.Timer` thread failures (uncaught exception kills all tasks) and tasks that fell due while another task held the timer thread |
| `COPY_ON_WRITE_COLLECTIONS` | Detect `CopyOnWriteArrayList`/`CopyOnWriteArraySet` with high write ratio (O(n) copy per write) |
| `STRING_BUILDER` | Detect `StringBuilder` mutated by multiple threads (not thread-safe; use `StringBuffer` or `ThreadLocal`) |

#### Context accessors for Phase 5 detectors

```java
AsyncTestContext.calendarDetector()           // CalendarDetector
AsyncTestContext.sharedCollectionDetector()   // SharedCollectionDetector
AsyncTestContext.timerDetector()              // TimerDetector
AsyncTestContext.copyOnWriteCollectionDetector()        // CopyOnWriteCollectionDetector
AsyncTestContext.stringBuilderDetector()      // StringBuilderDetector
```

#### CalendarDetector example

```java
@AsyncTest(threads = 4, includes = DetectorType.CALENDAR)
void testCalendarSharing() {
    Calendar cal = Calendar.getInstance();
    AsyncTestContext.calendarDetector()
        .registerCalendar(cal, "shared-calendar");

    cal.set(Calendar.YEAR, 2024);
    AsyncTestContext.calendarDetector()
        .recordSet(cal, "shared-calendar");
}
// Fix: use LocalDate/ZonedDateTime from java.time.* (immutable, thread-safe)
```

#### SharedCollectionDetector example

```java
@AsyncTest(threads = 4, includes = DetectorType.SHARED_COLLECTIONS)
void testSharedList() {
    List<String> list = new ArrayList<>();   // BUG: not thread-safe
    AsyncTestContext.sharedCollectionDetector()
        .registerCollection(list, "item-list", "ArrayList");

    list.add("item");
    AsyncTestContext.sharedCollectionDetector()
        .recordWrite(list, "item-list", "add");
}
// Fix: use ConcurrentHashMap, CopyOnWriteArrayList, or Collections.synchronizedList()
```

#### TimerDetector example

```java
@AsyncTest(threads = 2, includes = DetectorType.TIMER)
void testTimerUsage() {
    Timer timer = new Timer("my-timer");
    TimerDetector detector = AsyncTestContext.timerDetector();
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
@AsyncTest(threads = 4, includes = DetectorType.COPY_ON_WRITE_COLLECTIONS)
void testWriteHeavyCopyOnWrite() {
    CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
    AsyncTestContext.copyOnWriteCollectionDetector()
        .registerCollection(list, "event-list");

    list.add("event");
    AsyncTestContext.copyOnWriteCollectionDetector()
        .recordWrite(list, "event-list");
}
// Fix: use ConcurrentHashMap.newKeySet() or ConcurrentLinkedQueue for write-heavy workloads
```

#### StringBuilderDetector example

```java
@AsyncTest(threads = 4, includes = DetectorType.STRING_BUILDER)
void testSharedStringBuilder() {
    StringBuilder sb = new StringBuilder();   // BUG: not thread-safe
    AsyncTestContext.stringBuilderDetector()
        .registerBuilder(sb, "log-builder");

    sb.append("entry");
    AsyncTestContext.stringBuilderDetector()
        .recordAppend(sb, "log-builder");
}
// Fix: use ThreadLocal<StringBuilder> or build strings per-thread and join at the end
```

### Phase 8: Lifecycle & Structural Correctness (v1.6.0)

| DetectorType | Description |
|--------------|-------------|
| `EXECUTOR_SHUTDOWN` | Detect `ExecutorService` tasks submitted but never shut down, or shut down without `awaitTermination()` |
| `MUTABLE_MAP_KEY` | Detect `HashMap`/`HashSet` keys mutated after insertion, silently breaking future lookups |
| `NESTED_MONITOR_LOCKOUT` | Detect blocking ops (`wait`/`Future.get`/`lock`) attempted while holding a different monitor |
| `LOCK_DOWNGRADE` | Detect illegal read-to-write upgrade on `ReentrantReadWriteLock` (deadlocks immediately) |
| `INHERITABLE_THREAD_LOCAL` | Detect `InheritableThreadLocal` accessed from pooled threads (value frozen at thread-creation time, not task-submission time) |

#### Context accessors for Phase 8 detectors

```java
AsyncTestContext.executorShutdownDetector()             // ExecutorShutdownDetector
AsyncTestContext.mutableMapKeyDetector()                // MutableMapKeyDetector
AsyncTestContext.nestedMonitorLockoutDetector()         // NestedMonitorLockoutDetector
AsyncTestContext.lockDowngradeDetector()                // LockDowngradeDetector
AsyncTestContext.inheritableThreadLocalMisuseDetector() // InheritableThreadLocalMisuseDetector
```

### Phase 10: API Traps & Subtle Concurrency Bugs (v1.6.0)

| DetectorType | Description |
|--------------|-------------|
| `THREAD_LOCAL_CONTAMINATION` | Detect `ThreadLocal` set in task A read by task B on the same reused pooled thread |
| `ATOMIC_NON_ATOMIC_UPDATE` | Detect `get()` + `set()` on `Atomic*` without `compareAndSet()`, losing concurrent updates |
| `SYNCHRONIZED_COLLECTION_ITERATION` | Detect `Collections.synchronized*` iterated without holding the wrapper lock |
| `SHARED_FORMATTER` | Detect `Formatter`/`PrintWriter`/`PrintStream` accessed from multiple threads concurrently |
| `CONCURRENT_MAP_COMPUTE_RECURSION` | Detect a `compute*`/`merge` mapping function that re-enters its own map on the same thread, on the same key (the nested update is discarded) or on any other key (usually returns normally, leaving the map updated out of order). Nesting into a different map is not reported |
| `SYNCHRONIZED_ON_LITERAL` | Detect `synchronized` on interned `String` or cached `Integer`/`Long` [-128, 127] — JVM-wide shared monitor |
| `PUBLIC_LOCK_EXPOSURE` | Detect `synchronized(this)` on publicly accessible objects — enables external lock acquisition |
| `FORK_JOIN_TASK_BLOCKING` | Detect blocking calls (`sleep`/`wait`/`get`/IO) inside a `ForkJoinTask`, starving carrier threads |
| `OPTIMISTIC_READ_VALIDATION` | Detect `StampedLock` optimistic-read data used without `validate(stamp)` or after failed validation |
| `CF_COMMON_POOL_BLOCKING` | Detect blocking work inside `CompletableFuture` submitted without a custom `Executor` |

#### Context accessors for Phase 10 detectors

```java
AsyncTestContext.threadLocalContaminationDetector()         // ThreadLocalContaminationDetector
AsyncTestContext.atomicNonAtomicUpdateDetector()            // AtomicNonAtomicUpdateDetector
AsyncTestContext.synchronizedCollectionIterationDetector()  // SynchronizedCollectionIterationDetector
AsyncTestContext.sharedFormatterDetector()                  // SharedFormatterDetector
AsyncTestContext.concurrentMapComputeRecursionDetector()    // ConcurrentMapComputeRecursionDetector
AsyncTestContext.synchronizedOnLiteralDetector()            // SynchronizedOnLiteralDetector
AsyncTestContext.publicLockExposureDetector()               // PublicLockExposureDetector
AsyncTestContext.forkJoinTaskBlockingDetector()             // ForkJoinTaskBlockingDetector
AsyncTestContext.optimisticReadValidationDetector()         // OptimisticReadValidationDetector
AsyncTestContext.cfCommonPoolBlockingDetector()             // CompletableFutureCommonPoolBlockingDetector
```

### Phase 12: Operational & Hygiene Concurrency Issues (v0.10.0)

| DetectorType | Description |
|--------------|-------------|
| `INTERRUPT_SWALLOWING` | Detect `catch(InterruptedException)` blocks that swallow the signal without calling `Thread.currentThread().interrupt()` or rethrowing |
| `MDC_CONTEXT_LEAK` | Detect SLF4J MDC entries not cleared at task end, leaking into the next task on a reused pooled thread |
| `SYSTEM_PROPERTY_MUTATION` | Detect concurrent `System.setProperty()` / `clearProperty()` calls causing non-deterministic configuration |
| `FUTURE_IGNORED` | Detect `Future`s from `submit()` that are never inspected — exceptions from failed tasks are silently swallowed |
| `EXPLICIT_GC` | Detect `System.gc()` / `Runtime.gc()` invocations that trigger unpredictable STW pauses mid-test |
| `DEPRECATED_THREAD_API` | Detect calls to `Thread.stop()`, `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`, `Thread.countStackFrames()` |
| `SHARED_XML_PARSER` | Detect `DocumentBuilder` / `SAXParser` / `Transformer` / `XPath` instances accessed from multiple threads |
| `BOXED_PRIMITIVE_LOCK` | Detect `synchronized` on cached `Integer`/`Long` (−128..127), `Boolean.TRUE/FALSE`, or interned `String` literals |
| `SHARED_TIMEZONE` | Detect `TimeZone` instances mutated (`setRawOffset`, `setID`) from multiple threads |
| `UNCAUGHT_EXCEPTION_HANDLER` | Detect threads started without a custom `UncaughtExceptionHandler`, and with no JVM-wide default handler, that subsequently throw |

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
