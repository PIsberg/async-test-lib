# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

#### Phase 12: Operational & Hygiene Concurrency Issues
- **Interrupt Swallowing** (`detectInterruptSwallowing`) — detects `catch (InterruptedException)`
  blocks that neither rethrow the exception nor call `Thread.currentThread().interrupt()`,
  permanently suppressing the cooperative-cancellation signal and preventing executors and
  blocking operations from observing shutdown requests.
- **MDC Context Leak** (`detectMdcContextLeak`) — detects SLF4J MDC entries that are not
  cleared at task end, causing key/value leakage to the next task run on a reused pooled
  thread (wrong request-ID, user, or trace-ID in logs).
- **System Property Mutation** (`detectSystemPropertyMutation`) — detects concurrent
  `System.setProperty()` or `clearProperty()` calls during the test run, which introduce
  non-deterministic configuration and test pollution that survives to subsequent test methods.
- **Future Ignored** (`detectFutureIgnored`) — detects `Future` / `CompletableFuture` instances
  returned from `submit()` that are never inspected via `get()`, `isDone()`, `isCancelled()`, or
  `cancel()`, causing exceptions from failed tasks to be silently discarded.
- **Explicit GC** (`detectExplicitGc`) — detects `System.gc()` or `Runtime.gc()` invocations
  during concurrent execution, which trigger unpredictable stop-the-world pauses that corrupt
  latency measurements and concurrency-timing tests.
- **Deprecated Thread API** (`detectDeprecatedThreadApi`) — detects calls to `Thread.stop()`,
  `Thread.suspend()`, `Thread.resume()`, `Thread.destroy()`, and `Thread.countStackFrames()`,
  which are unsafe (`stop()` releases all monitors, breaking invariants; `suspend/resume` are
  inherently deadlock-prone) and were removed or made no-ops in Java 20+.
- **Shared XML Parser** (`detectSharedXmlParser`) — detects `DocumentBuilder`, `SAXParser`,
  `Transformer`, and `XPath` instances accessed concurrently from multiple threads; all are
  not thread-safe and produce corrupted parse results or `ConcurrentModificationException`s
  under concurrent use.
- **Boxed Primitive Lock** (`detectBoxedPrimitiveLock`) — detects `synchronized` blocks that
  lock on cached boxed primitives (`Integer`/`Long` in range −128..127, `Boolean.TRUE/FALSE`,
  interned `String` literals), which are JVM-global shared instances causing unexpected
  contention with unrelated code using the same value as a lock.
- **Shared TimeZone** (`detectSharedTimeZone`) — detects `TimeZone` instances whose mutable
  state (`setRawOffset`, `setID`) is modified from multiple threads, silently producing wrong
  date/time arithmetic.
- **Uncaught Exception Handler** (`detectUncaughtExceptionHandler`) — detects threads started
  without a custom `UncaughtExceptionHandler` that subsequently throw, causing the exception
  to be silently discarded from the submitter's perspective (only printed to stderr via the
  default thread-group handler).

## [0.9.0] - 2026-05-06

### Changed

- **BREAKING**: Java package renamed from `com.github.asynctest` to `se.deversity.asynctest`. Consumers must update all `import` statements. Maven coordinates (`se.deversity.async-test-lib:async-test-lib`) are unchanged.
  - Note: benchmark baselines stored under `load-tests/results/0.7.0/` and `load-tests/results/0.8.0/` reference the old package name in JMH output — this is expected and those files are left as historical data.

### Added

#### Phase 11: Thread-Safety of Additional Types & Patterns
- **Shared Matcher** (`detectSharedMatcher`) — detects `java.util.regex.Matcher` instances
  accessed concurrently from multiple threads. `Pattern` is thread-safe but `Matcher` holds
  mutable per-match state (position, group offsets, last-append position); concurrent use
  produces incorrect matches or `StringIndexOutOfBoundsException`. Fix: call
  `pattern.matcher(input)` inside each thread rather than sharing one `Matcher`.
- **Shared DecimalFormat** (`detectSharedDecimalFormat`) — detects `java.text.DecimalFormat`
  and `java.text.NumberFormat` instances accessed concurrently. Concurrent `format()` /
  `parse()` calls corrupt internal multiplier and grouping state, producing garbled output
  without any exception — the numeric-formatting analogue of `SimpleDateFormat` misuse.
  Fix: `ThreadLocal<DecimalFormat>` or create a new instance per call.
- **Weak Reference Race** (`detectWeakReferenceRace`) — detects two failure modes around
  `WeakReference` / `SoftReference`: (1) `get()` result used without a null check
  (`ERROR`) — the referent may be collected at any time, including between the
  `get()` call and the first dereference; (2) referent collected mid-test (`WARN`) — the
  same reference returned non-null from some threads and null from others, revealing code
  paths that do not handle null on every branch.
- **Stateful Lambda** (`detectStatefulLambda`) — detects `Runnable` / `Callable` / lambda
  instances that capture mutable containers (e.g. `int[]`, `Object[]`, wrapper objects) and
  are executed concurrently from multiple threads while mutating those captures. The JVM
  enforces *effectively final* for captured variables, but captured *containers* are mutable —
  a common, hard-to-spot data race. Fix: `AtomicInteger` / `LongAdder`, or create a new
  lambda instance per task.
- **Shared MessageDigest** (`detectSharedMessageDigest`) — detects `java.security.MessageDigest`
  instances accessed concurrently. `MessageDigest` is not thread-safe: every `update()` and
  `digest()` call mutates internal digest state (running hash buffer, byte count, padding).
  Concurrent access silently corrupts the hash without throwing any exception — one of the
  hardest concurrency bugs to diagnose in production. Fix: `MessageDigest.getInstance()` per
  thread or `ThreadLocal<MessageDigest>`.

## [0.8.0] - 2026-05-02

### Added

#### Phase 8: Lifecycle & Structural Correctness
- **Executor Shutdown** (`detectExecutorShutdown`) — detects `ExecutorService` instances
  that have tasks submitted but are never shut down (thread leak), or are shut down without
  a subsequent `awaitTermination()` call (in-flight tasks silently abandoned)
- **Mutable Map Key** (`detectMutableMapKeys`) — detects objects used as `HashMap` /
  `HashSet` keys that are mutated after insertion; mutation changes the hash bucket,
  silently breaking all future lookups and removes
- **Nested Monitor Lockout** (`detectNestedMonitorLockout`) — detects threads that attempt
  a blocking operation (`wait()`, `Future.get()`, `Lock.lock()`) while already holding a
  monitor on a *different* object, a reliable path to deadlock
- **Lock Downgrade** (`detectLockDowngrade`) — detects illegal read-to-write upgrade
  attempts on `ReentrantReadWriteLock`; the JDK does not support upgrades and the attempt
  deadlocks immediately
- **InheritableThreadLocal Misuse** (`detectInheritableThreadLocalMisuse`) — detects
  `InheritableThreadLocal` values accessed from thread-pool threads; the value is inherited
  at thread-creation time rather than task-submission time, causing stale or cross-task
  context contamination

#### Phase 9: Repository & Environment State
- **Uncommitted Changes** (`detectUncommittedChanges`) — detects untracked or uncommitted
  Git files that may affect test reproducibility; reports a low-severity issue if the
  repository is not in a clean state (requires `git` to be available in the PATH)

#### Phase 10: API Traps & Subtle Concurrency Bugs
- **ThreadLocal Contamination** (`detectThreadLocalContamination`) — detects `ThreadLocal`
  values set in one task that are read by the next task on the same pooled thread without
  an intervening `remove()` or `set()`; common source of stale MDC loggers and security
  contexts in servlet/Spring applications
- **Atomic Non-Atomic Update** (`detectAtomicNonAtomicUpdates`) — detects `get()` followed
  by `set()` on `AtomicInteger` / `AtomicLong` / `AtomicReference` without
  `compareAndSet()`; the data structure guarantees per-operation atomicity but a
  read-modify-write without CAS silently loses concurrent updates
- **Synchronized Collection Iteration** (`detectSynchronizedCollectionIteration`) —
  detects `Collections.synchronizedList` / `synchronizedMap` / `synchronizedSet` wrappers
  iterated without holding the wrapper's intrinsic lock; the Javadoc explicitly requires
  `synchronized(list) { iterator }` but the compiler never enforces it
- **Shared Formatter** (`detectSharedFormatter`) — detects `java.util.Formatter`,
  `PrintWriter`, and `PrintStream` (including `System.out` / `System.err`) accessed from
  multiple threads without external synchronization
- **ConcurrentMap Compute Recursion** (`detectConcurrentMapComputeRecursion`) — detects
  recursive `computeIfAbsent` / `compute` / `merge` calls on the same `ConcurrentHashMap`
  key from the same thread; causes an infinite loop on Java 8 and
  `IllegalStateException` on Java 9+
- **Synchronized on Literal** (`detectSynchronizedOnLiteral`) — detects `synchronized`
  blocks on interned `String` literals or JVM-cached `Integer` / `Long` values
  (range [-128, 127]); those monitors are shared JVM-wide, silently coupling unrelated
  classes through a single monitor
- **Public Lock Exposure** (`detectPublicLockExposure`) — detects `synchronized(this)` on
  objects that are publicly accessible; external callers can acquire the same lock,
  causing unexpected deadlock or starvation
- **ForkJoinTask Blocking** (`detectForkJoinTaskBlocking`) — detects blocking calls
  (`Thread.sleep`, `Object.wait`, `Future.get`, blocking I/O) inside a `ForkJoinTask`
  body; blocks a carrier thread and starves the bounded pool for all other tasks
- **Optimistic Read Validation** (`detectOptimisticReadValidation`) — detects
  `StampedLock.tryOptimisticRead()` data used without calling `validate(stamp)`, or data
  continued to be used after a failed validation, producing silent torn-snapshot corruption
- **CompletableFuture Common-Pool Blocking** (`detectCFCommonPoolBlocking`) — detects
  blocking operations inside `CompletableFuture` stages submitted to the common
  `ForkJoinPool` (i.e. created without a custom `Executor`); starves the pool for parallel
  streams and all other JVM callers

#### Documentation & examples
- CLAUDE.md updated with Phase 8, Phase 9, and Phase 10 detector descriptions

#### Phase 2: Additional Concurrency Detectors
- **Lock Contention** (`detectLockContention`) — detects monitors where more than 20% of
  acquire attempts are blocked (or ≥5 contention events), flagging hot-lock hotspots that
  degrade throughput and scalability under concurrent load
- **Synchronized on Non-Final Field** (`detectSynchronizedNonFinal`) — detects the
  anti-pattern of locking on a field that is not declared `final`; if the reference is
  reassigned between invocations, two threads may synchronize on *different* objects,
  silently breaking mutual exclusion
- **Missed Signal** (`detectMissedSignals`) — detects `notify()` / `notifyAll()` calls
  made when no thread is currently waiting on the condition; the signal is silently
  discarded, causing threads that later call `wait()` to block indefinitely
- **Lazy Initialization Race** (`detectLazyInitRace`) — detects fields that are initialized
  by multiple concurrent threads because the null-guard is unsynchronized or the field is
  not `volatile`; also flags non-volatile fields where several threads simultaneously
  observe `null`, a visibility risk even when only one initialization occurs

#### Documentation & examples
- New example project `05-lock-contention` demonstrating coarse-grained lock contention
  on `RequestCounterService` and the LockContentionDetector hotspot report

## [0.7.0] - 2026-04-17

### Added

#### Phase 6: Virtual Thread Concurrency (Java 21+)
- **Structured Concurrency Misuse** (`detectStructuredConcurrencyIssues`) — detects unclosed
  `StructuredTaskScope`, subtask results accessed before `join()`, scopes closed without
  `join()`, and empty scopes with no subtasks forked
- **Virtual Thread Context Leaks** (`detectVirtualThreadContextLeaks`) — detects `ThreadLocal`
  values set in virtual threads but never removed, `InheritableThreadLocal` misuse inside
  virtual threads, and excessive per-thread `ThreadLocal` counts (prefer `ScopedValue`)
- **ScopedValue Misuse** (`detectScopedValueMisuse`) — detects `ScopedValue.get()` calls
  outside an active binding, unintentional re-binding in nested scopes, and excessive
  simultaneous binding counts
- **Virtual Thread CPU-Bound Tasks** (`detectVirtualThreadCpuBoundTasks`) — detects
  CPU-intensive tasks running on virtual threads without yielding beyond a configurable
  threshold (default 50 ms); monopolising a carrier thread negates virtual-thread scalability
- **Virtual Thread Carrier Exhaustion** (`detectVirtualThreadCarrierExhaustion`) — detects
  scenarios where the count of concurrently blocked virtual threads approaches or exceeds
  the available carrier platform threads, causing scheduler starvation

#### Phase 7: High-Level Concurrency Patterns
- **HTTP Client Concurrency Issues** (`detectHttpClientIssues`) — detects unclosed HTTP
  responses, connection pool exhaustion, and requests initiated but never completed
- **Stream Closing** (`detectStreamClosing`) — detects `InputStream`/`OutputStream`/
  `Reader`/`Writer` instances opened but never closed in concurrent code
- **Cache Concurrency** (`detectCacheConcurrency`) — detects `HashMap`/`LinkedHashMap`
  used as a cache without synchronisation and concurrent read/write races
- **CompletableFuture Chain Issues** (`detectCompletableFutureChainIssues`) — detects
  missing exception handlers, unjoined futures, and improper chain construction

#### Documentation & examples
- New example project `04-virtual-thread-context-leak` demonstrating virtual thread
  context leak detection with a `RequestScopedService`
- Extended consumer-fixture with Phase 6 and Phase 7 usage examples
- README Phase 6 and Phase 7 deep-dive sections with usage patterns and fix guidance

### Maintenance
- Bump `step-security/harden-runner` 2.17.0 → 2.18.0
- Bump `github/codeql-action` 4.35.1 → 4.35.2
- Bump `gradle/actions` 4 → 6
- Bump `org.sonatype.central:central-publishing-maven-plugin` to 0.10.0

## [0.6.2] - 2026-04-13

### Fixed
- Jazzer fuzzing CI: added `repo.maven.apache.org:443` to the harden-runner egress allow-list so Maven can resolve plugins (e.g. `maven-source-plugin`) during `test-compile`.

## [0.5.1] - 2026-04-12

### Fixed
- Re-release of 0.5.0 after initial deployment to Maven Central failed due to a duplicate component conflict.

## [0.5.0] - 2026-04-12

First public release on Maven Central.

### Added

#### Core framework
- `@AsyncTest` annotation — drop-in replacement for `@Test` that runs the test body
  concurrently across a configurable number of threads and invocation rounds
- `@BeforeEachInvocation` / `@AfterEachInvocation` lifecycle hooks that fire once per
  invocation round (complementing JUnit's `@BeforeEach` / `@AfterEach`)
- `AsyncTestContext` — thread-local access to per-test runtime state and detector instances
- Barrier synchronisation via `CyclicBarrier` to maximise thread collision probability
- Virtual thread support (`useVirtualThreads = true`) with stress modes `LOW`, `MEDIUM`,
  `HIGH`, and `EXTREME` (up to 100 000 concurrent virtual threads)
- Configurable timeout per test (`timeoutMs`)
- Benchmarking mode with regression threshold and fail-on-regression flag

#### Phase 1 detectors (always-on)
- **Deadlock detection** — identifies circular lock chains and reports which threads are
  waiting for which locks
- **Memory visibility** — tracks field values across invocations to detect missing
  `volatile` / synchronisation
- **Race condition forcing** — barrier-synchronised thread collisions expose data races
  that standard sequential tests miss
- **Livelock detection** — recognises threads spinning without making progress
- **Starvation detection** — flags threads that are consistently scheduled last

#### Phase 2 detectors (opt-in)
- False sharing (`detectFalseSharing`)
- ABA problem in lock-free code (`detectABAProblem`)
- Lock order validation (`validateLockOrder`)
- Constructor safety / early publication (`validateConstructorSafety`)
- Memory ordering violations (`detectMemoryOrderingViolations`)
- Synchroniser monitoring — `CountDownLatch`, `CyclicBarrier`, `Semaphore`
  (`monitorSynchronizers`)
- Thread pool saturation and queue exhaustion (`monitorThreadPool`)
- Read/write lock fairness (`monitorReadWriteLockFairness`)
- Async pipeline monitoring (`monitorAsyncPipeline`)
- Spurious wakeup / lost notification detection (`detectWakeupIssues`)

#### Phase 3 detectors (opt-in)
- `CompletableFuture` completion leak detection (`detectCompletableFutureCompletionLeaks`)
- Thread pool deadlock detection (`detectThreadPoolDeadlocks`)
- Thread leak detection (`detectThreadLeaks`)
- Sleep-in-lock detection (`detectSleepInLock`)
- Unbounded queue detection (`detectUnboundedQueue`)
- Thread starvation (`detectThreadStarvation`)
- Phaser misuse (`monitorPhaser`)
- Wait-without-timeout detection (`monitorWaitTimeout`)

#### Convenience
- `detectAll = true` — enables all Phase 1, 2 and 3 detectors in one flag
- `excludes` — selectively disable individual detector types when using `detectAll`

#### Build & distribution
- Maven and Gradle (Kotlin DSL) build support
- Published to Maven Central
- Sources JAR, Javadoc JAR, and CycloneDX SBOM generated on every release
- Artifacts signed with GPG and cosign (keyless OIDC) on every release
- OpenSSF Scorecard integration
- Codecov coverage reporting

### Examples
- `01-completablefuture-exception-handling` — demonstrates unhandled exceptions in async
  chains that standard tests miss
- `02-visibility-volatile-flag` — demonstrates memory visibility bugs caused by a missing
  `volatile` keyword

[0.8.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.8.0
[0.7.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.7.0
[0.6.2]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.6.2
[0.5.1]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.5.1
[0.5.0]: https://github.com/PIsberg/async-test-lib/releases/tag/v0.5.0
