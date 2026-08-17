# Using Async Test Library

## Installation

### Via Maven (GitHub Packages)

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>se.deversity.async-test-lib</groupId>
    <artifactId>async-test-lib</artifactId>
    <version>1.9.4</version>
    <scope>test</scope>
</dependency>
```

Configure the GitHub Packages repository in your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://repo1.maven.org/maven2</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Via Gradle

```gradle
repositories {
    maven {
        url = uri("https://repo1.maven.org/maven2")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    testImplementation 'se.deversity.async-test-lib:async-test-lib:1.9.4'
}
```

## Basic Usage

### 1. Import the annotation

```java
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
```

### 2. Annotate your test method

```java
public class MyAsyncTests {
    
    @AsyncTest(
        threads = 10,
        invocations = 100,
        detectAll = true
    )
    void testConcurrentAccess() {
        // All 139 detectors are enabled!
    }
}
```

## Configuration Options

### Core Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threads` | int | 10 | Number of threads to spawn |
| `invocations` | int | 100 | Number of times the concurrent round runs |
| `timeoutMs` | long | 5000 | Test timeout in milliseconds |
| `useVirtualThreads` | boolean | true | Use Java 21+ virtual threads |
| `virtualThreadStressMode` | String | "OFF" | Virtual thread stress level (OFF, LOW, MEDIUM, HIGH, EXTREME) |
| `detectAll` | boolean | false | **Enable ALL detectors in one shot (Recommended)** |
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
| `detectTimerIssues` | boolean | true | Detect `java.util.Timer` thread failures (uncaught exception kills all tasks) and long-running tasks |
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
    AsyncTestContext.timerMonitor()
        .registerTimer(timer, "my-timer");

    AsyncTestContext.timerMonitor()
        .recordTaskRun(timer, "my-timer", "task-1");
    AsyncTestContext.timerMonitor()
        .recordTaskComplete(timer, "my-timer", "task-1");
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
| `detectConcurrentMapComputeRecursion` | boolean | true | Detect recursive `computeIfAbsent` on same key from same thread (infinite loop or `IllegalStateException`) |
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
| `detectUncaughtExceptionHandler` | boolean | true | Detect threads started without a custom `UncaughtExceptionHandler` that subsequently throw |

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

## Agent Instrumentation (optional)

Detectors normally observe field access via explicit recording calls (e.g.
`AsyncTestContext.*Detector().recordAccess(...)`). The optional
`se.deversity.asynctest.agent.AsyncTestAgent` — a [Byte Buddy](https://bytebuddy.net) Java
agent — records getter/setter access **automatically, with no source changes**, and can feed
those events straight into a live `AtomicityValidator` via `TelemetryBridge`.

Attach it one of three ways:

```
# 1. Launch flag (static), optionally scoped:
-javaagent:async-test-agent-<version>.jar=includes=com.myapp;excludes=com.myapp.dto,debug=true
```

```java
// 2. Runtime self-attach from test setup (needs -Djdk.attach.allowAttachSelf=true):
@BeforeAll
static void attachAgent() {
    se.deversity.asynctest.agent.AsyncTestAgent.selfAttach("includes=com.myapp");
}
```

It is strictly opt-in — if you do not attach the agent, nothing changes. See
**[AGENT.md](AGENT.md)** for the full guide: the WHY (observer-effect / Heisenbug), all three
attachment paths with Maven and Gradle snippets, consuming events via `TelemetryBridge`, scope
and filtering, `debug=true` diagnostics, limitations, and a troubleshooting table.

## Running without the annotation: `AsyncTestRunner` (1.9.4)

`@AsyncTest` is a Jupiter `@TestTemplate`, so it only runs inside a Jupiter test class. Spock,
ScalaTest, MUnit, kotest and `clojure.test` are engines or frameworks of their own and a Jupiter
template does not run inside them. `AsyncTestRunner` is the same engine as a method call: build
the configuration, hand over the body, read the findings.

```java
AsyncTestConfig cfg = AsyncTestConfig.builder()
        .threads(8).invocations(200).detectAll(true).failOn(FailOn.NONE).build();
AsyncFindings findings = AsyncTestRunner.run(cfg, () -> counter.increment());
findings.assertReported("RaceConditionDetector");
```

Three things to know, each different from the annotation:

- **Detectors are opt-in on the builder.** The annotation defaults to `detectAll = true`;
  `AsyncTestConfig.builder()` defaults every detector to off. A config without `detectAll(true)`,
  a `preset(...)` or individual `detectXxx(true)` calls runs the body under contention and
  detects nothing.
- **What it throws is what the annotated path throws.** A failing body surfaces as the engine's
  `AssertionError` with the body's exception as its cause (N workers on one defect are collapsed
  into one error); a hung body as the timeout `AssertionError`; findings at or above `failOn` as
  the gate's `AssertionError` after a clean run. On a clean run the returned `AsyncFindings` holds
  every finding; when the run throws, register your own `AsyncFindings.collect()` around the call
  if you need them.
- **Identity.** The engine names a run after the method it executes; a body has none, so every
  programmatic run is `se.deversity.asynctest.AsyncTestRunner$BodyHolder#run` in the `runner.*`
  log events, in the `failOn` message and as the finding-baseline id. Baselining a finding for one
  programmatic run suppresses it for all of them. `run(name, cfg, body)` puts your name in the
  `runner.programmatic` log event only.

Inside the body, `AsyncTestContext.get()` and the `recordXxx` hooks work as they do in an
annotated method, and the licence gate applies as it does there.

## Manual Legacy Diagnostics

For older Java async patterns that need explicit instrumentation, instantiate the diagnostics directly:

```java
NotifyAllValidator notifyValidator = new NotifyAllValidator();
LazyInitValidator lazyInitValidator = new LazyInitValidator();
FutureBlockingDetector futureBlockingDetector = new FutureBlockingDetector();
ExecutorDeadlockDetector executorDeadlockDetector = new ExecutorDeadlockDetector();
LatchMisuseDetector latchMisuseDetector = new LatchMisuseDetector();
```

Use these for:
- `wait()`/`notify()` vs `notifyAll()` bugs
- unsafe lazy initialization and broken double-checked locking
- blocking on sibling futures inside bounded executors
- single-thread or bounded executor self-deadlocks
- missing `CountDownLatch.countDown()` paths

## Examples

### Example 1: Basic Race Condition Detection

```java
public class AtomicCounterTest {
    private int counter = 0;
    
    @AsyncTest(threads = 20, invocations = 100, detectAll = true)
    void testRaceCondition() {
        counter++;  // Race condition: unsynchronized increment
    }
}
```

### Example 2: Opting out of expensive detectors

```java
public class PerformanceSensitiveTest {
    @AsyncTest(
        detectAll = true,
        excludes = { DetectorType.FALSE_SHARING, DetectorType.VISIBILITY }
    )
    void testHighThroughput() {
        // Enables everything EXCEPT false sharing and visibility detection
    }
}
```

### Example 3: Deadlock Detection

```java
public class DeadlockTest {
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    
    @AsyncTest(threads = 5, invocations = 50, detectAll = true)
    void testDeadlock() {
        if (System.nanoTime() % 2 == 0) {
            synchronized (lock1) {
                synchronized (lock2) {
                    // Perform work
                }
            }
        } else {
            synchronized (lock2) {
                synchronized (lock1) {
                    // Opposite lock order - deadlock!
                }
            }
        }
    }
}
```

### Example 4: Virtual Thread Stress Testing

```java
public class VirtualThreadStressTest {
    private final List<String> list = Collections.synchronizedList(new ArrayList<>());
    
    @AsyncTest(
        useVirtualThreads = true,
        virtualThreadStressMode = "HIGH",
        threads = 100000,  // Create 100,000 virtual threads
        invocations = 10,
        timeoutMs = 10000
    )
    void testVirtualThreadScalability() {
        list.add("item-" + Thread.currentThread().threadId());
    }
}
```

## Analyzing Results

### First, know what kind of finding you are reading

Not every detector makes the same kind of claim. Some can tell broken code from the correctly
synchronized version of that same code and are safe to fail a build on. Others only observe that an
object was touched by two threads, which correct code does too — those are a prompt to check your
synchronization, not a verdict. Which is which is measured, not asserted:
[DETECTOR_CATALOG.md § Trust tiers](DETECTOR_CATALOG.md#trust-tiers).

Start with `failOn = CRITICAL`, which gates on the trustworthy end of the scale.

### Adopting into an existing suite

An established codebase will produce findings the first time `detectAll` runs, and a gate that is
red from the first commit gets switched off. Record what you already have, gate on what is new:

```bash
mvn test -Dasync-test.baseline=async-test-baseline.txt -Dasync-test.baseline.update=true  # once
mvn test -Dasync-test.baseline=async-test-baseline.txt                                    # thereafter
```

Commit the file and review its diff. Full mechanics, including what a baseline does not suppress:
[CI_INTEGRATION.md](CI_INTEGRATION.md#adopting-into-a-codebase-that-already-has-findings).

### Reproducing a failure

Every failing run prints the seed that produced the interleaving:

```
[AsyncTest] Failure with replaySeed=8134729471193L — paste into @AsyncTest(replaySeed=...) to reproduce.
```

Paste it into the annotation and the same schedule is replayed, which is the difference between a
flaky failure and one you can debug.

When a test fails, the library provides detailed diagnostics:

```
[RACE CONDITION] Field 'counter' accessed without synchronization
  - Expected final value: 2000
  - Actual final value: 1847
  - Missing synchronization at: AtomicCounterTest.testRaceCondition:15

[DEADLOCK DETECTED]
  Thread-1 waiting for lock@0x7fa1234 held by Thread-2
  Thread-2 waiting for lock@0x7fa5678 held by Thread-1
  Thread dump saved to: target/deadlock-dump-2024-03-24.txt

[VISIBILITY ISSUE]
  Field 'flag' accessed without volatile modifier
  - Thread-1 wrote value at 14:23:45.123
  - Thread-2 never saw the update (timed out)
  - Suggestion: Add 'volatile' to field declaration
```

## Best Practices

### 1. Use detectAll = true
For most application code, `detectAll = true` is the best starting point. It provides maximum coverage with zero boilerplate.

### 2. Use excludes selectively
If a specific detector (like `FALSE_SHARING`) causes too much overhead in a large test suite, exclude it rather than turning off everything.

### 3. Start Simple
```java
@AsyncTest(detectAll = true)
void simpleTest() { }
```

### 4. Provide Sufficient Timeout
Stress tests with many threads or all detectors enabled may need more time.
```java
@AsyncTest(detectAll = true, timeoutMs = 10000)
void deepStressTest() { }
```

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/PIsberg/async-test-lib/issues
- Documentation: https://github.com/PIsberg/async-test-lib/wiki

## License

MIT License - See LICENSE file for details
