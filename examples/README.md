# Async Test Library - Examples

Real-world examples demonstrating common Java concurrency bugs that `@AsyncTest` finds but standard `@Test` misses.

## What these prove, and what they do not

Most examples ship the buggy service, a sequential `@Test` that passes on it, and an
`@AsyncTest` that exposes the bug. **Those `@AsyncTest` demonstrations are `@Disabled`**: 99 of
the 148 examples have one. That is deliberate: they demonstrate code that fails, so enabling
them would make the examples pipeline permanently red. Each carries a reason saying so, and
removing the annotation is the intended way to watch the bug surface.

The newer examples take a different shape, and it is the better one. Rather than a disabled
demonstration, they drive the detector's own recording API from ordinary `@Test` methods and
assert on the report: clean usage stays silent, the buggy pattern is flagged, and the severity
is pinned. Those tests **run in CI**, so they prove the detector still behaves as documented
rather than only that the example compiles. Forty examples are written this way, and every
example from 136 onwards is.

The distinction matters when reading the pipeline's green tick. For the disabled majority, the
examples pipeline builds all 148 and runs their enabled tests, so it proves those examples
**compile and keep working against the current library** — it does not prove any detector
fires. The check that does that for the library itself is `DetectionCoverageTest` in
`async-test-lib`, which runs real buggy code through a real `@AsyncTest` and asserts on the
detector reporting channel.

The other thing these examples make concrete is how a detector gets its data. Only a few, such as
`DeadlockDetector`, observe the JVM directly and need nothing from you. Most need the test body to
tell them what happened, which is why you will see calls like

```java
AsyncTestContext.sharedCollectionMonitor().recordWrite(events, "event-log", "add");
```

in the disabled demonstrations. The alternative to writing those by hand is the agent, which weaves
JavaBean accessors and feeds the detectors for you, see [../docs/AGENT.md](../docs/AGENT.md).

## Available Examples

| # | Example | Primary Detector | Async Problem | Severity |
|---|---------|------------------|---------------|----------|
| 01 | [CompletableFuture Exception Handling](01-completablefuture-exception-handling/) | `CompletableFutureExceptionDetector` | Unhandled exceptions in async chains cause silent data loss | 🔴 Critical |
| 02 | [Visibility/Volatile Flag](02-visibility-volatile-flag/) | `VisibilityMonitor` | Missing `volatile` on shared flags causes threads to never see shutdown signals | 🔴 Critical |
| 03 | [Shared Non-Thread-Safe Collection](03-shared-collection/) | `SharedCollectionDetector` | ArrayList/HashMap shared across threads causes data loss and corruption | 🔴 Critical |
| 04 | [Virtual Thread Context Leak](04-virtual-thread-context-leak/) | `VirtualThreadContextLeakDetector` | ThreadLocal leaks in virtual threads cause memory leaks | 🟡 High |
| 05 | [Lock Contention](05-lock-contention/) | `LockContentionDetector` | Many threads competing for one monitor — wait time dominates, throughput collapses before the CPUs are busy | 🟡 High |
| 06 | [Deadlock](06-deadlock/) | `DeadlockDetector` | Two threads acquire the same pair of locks in opposite order — circular wait, neither can proceed | 🔴 Critical |
| 07 | [Livelock](07-livelock/) | `LivelockDetector` | Two nodes back off and retry without delay — threads stay active but make no progress | 🔴 Critical |
| 08 | [Race Condition](08-race-condition/) | `RaceConditionDetector` | Non-atomic check-then-update lets multiple threads pass the stock threshold, driving count negative | 🔴 Critical |
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
| 21 | [Busy Wait](21-busy-wait/) | `BusyWaitDetector` | Tight spin loop polling an empty queue wastes CPU and prevents other threads from running | 🟡 High |
| 22 | [Atomicity Violation](22-atomicity-violation/) | `AtomicityValidator` | Non-atomic read-modify-write on a `volatile long` loses increments under concurrent load | 🔴 Critical |
| 23 | [ThreadLocal Leak](23-thread-local-leak/) | `ThreadLocalMonitor` | Request context stored in `ThreadLocal` never cleared — next task on the reused thread sees stale auth data | 🟡 High |
| 24 | [Interrupt Mishandling](24-interrupt-mishandling/) | `InterruptMonitor` | `catch(InterruptedException)` without restoring the interrupt flag breaks cooperative cancellation | 🔴 Critical |
| 25 | [Executor Self-Deadlock](25-executor-deadlock/) | `ExecutorDeadlockDetector` | Task on a single-thread pool submits a subtask to the same pool and blocks waiting — the subtask never runs | 🔴 Critical |
| 26 | [Future Blocking](26-future-blocking/) | `FutureBlockingDetector` | Pool workers call `Future.get()` on futures submitted to the same bounded pool — starvation when all threads block | 🔴 Critical |
| 27 | [Latch Misuse](27-latch-misuse/) | `LatchMisuseDetector` | `countDown()` in both `catch` and `finally` blocks causes premature latch completion and races to the next phase | 🟡 High |
| 28 | [Unsafe Lazy Init](28-lazy-init/) | `LazyInitValidator` | Double-checked locking without `volatile` — partially-constructed singleton visible to other threads | 🔴 Critical |
| 29 | [ABA Problem](29-aba-problem/) | `ABAProblemDetector` | Lock-free stack CAS succeeds despite node being recycled — classic A→B→A problem corrupts the stack | 🔴 Critical |
| 30 | [False Sharing](30-false-sharing/) | `FalseSharingDetector` | Adjacent `volatile long` fields on the same cache line cause coherence traffic between threads updating independent counters | 🟡 High |
| 31 | [Lock Order Violation](31-lock-order-violation/) | `LockOrderValidator` | Transfer service acquires account locks in source-first order — two concurrent opposite transfers create a circular dependency | 🔴 Critical |
| 32 | [RW Lock Starvation](32-rwlock-starvation/) | `ReadWriteLockMonitor` | Non-fair `ReentrantReadWriteLock(false)` lets readers cut ahead of waiting writers indefinitely — writes never complete | 🟡 High |
| 33 | [1.5.0/1.6.0 Feature Tour](33-1.5.0-feature-tour/) | *(tour, not a bug)* | Runnable tour of the public API: `Preset`, `threadCounts`, `replaySeed`, `awaitAsync`, scoped listeners, `MarkdownFormatter` | 🟢 Low |
| 34 | [Blocking Queue](34-blocking-queue/) | `BlockingQueueDetector` | Fire-and-forget `offer()` holds a bounded queue at capacity and discards the rejected items; `poll()` NPE on null return | 🟡 High |
| 35 | [Calendar Misuse](35-calendar-misuse/) | `CalendarDetector` | Shared `Calendar` instance mutated concurrently produces wrong date conversions | 🔴 Critical |
| 36 | [Cache Concurrency](36-cache-concurrency/) | `CacheConcurrencyDetector` | Plain `HashMap` used as a concurrent cache causes data loss and `ConcurrentModificationException` | 🔴 Critical |
| 37 | [CF Chain Issues](37-cf-chain/) | `CompletableFutureChainDetector` | Fire-and-forget `CompletableFuture` chains swallow exceptions silently | 🟡 High |
| 38 | [CF Common-Pool Blocking](38-cf-common-pool-blocking/) | `CompletableFutureCommonPoolBlockingDetector` | `supplyAsync()` + blocking `.get()` inside the same common pool starves it | 🔴 Critical |
| 39 | [CF Completion Leak](39-cf-completion-leak/) | `CompletableFutureCompletionLeakDetector` | `CompletableFuture` created but `complete()` never called — waiting threads block forever | 🟡 High |
| 40 | [ConcurrentMap Recursion](40-concurrent-map-recursion/) | `ConcurrentMapComputeRecursionDetector` | `computeIfAbsent` lambda calls `computeIfAbsent` on the same map — recursive deadlock | 🔴 Critical |
| 41 | [Concurrent Modification](41-concurrent-modification/) | `ConcurrentModificationDetector` | `ArrayList` iterated while another thread calls `add()` — `ConcurrentModificationException` | 🔴 Critical |
| 42 | [Condition Variable](42-condition-variable/) | `ConditionVariableDetector` | `signal()` instead of `signalAll()` leaves threads waiting indefinitely | 🟡 High |
| 43 | [Copy-On-Write Misuse](43-copy-on-write/) | `CopyOnWriteCollectionDetector` | `CopyOnWriteArrayList` on a write-heavy path — O(n) copy per write degrades throughput | 🟡 High |
| 44 | [CountDownLatch Misuse](44-count-down-latch/) | `CountDownLatchDetector` | `countDown()` skipped in one code path — `await()` blocks forever | 🔴 Critical |
| 45 | [CyclicBarrier Broken](45-cyclic-barrier/) | `CyclicBarrierDetector` | Exception before `await()` breaks the barrier — all subsequent arrivals get `BrokenBarrierException` | 🟡 High |
| 46 | [Daemon Thread Hygiene](46-daemon-thread/) | `DaemonThreadHygieneDetector` | Non-daemon background threads prevent JVM from shutting down | 🟢 Low |
| 47 | [Double-Checked Locking](47-double-checked-locking/) | `DoubleCheckedLockingDetector` | DCL without `volatile` — partially constructed singleton visible to other threads | 🔴 Critical |
| 48 | [Exchanger Misuse](48-exchanger-misuse/) | `ExchangerDetector` | Odd-numbered callers leave one thread blocked in `exchange()` without a partner | 🟡 High |
| 49 | [Executor Shutdown Leak](49-executor-shutdown/) | `ExecutorShutdownDetector` | `ExecutorService` created but `shutdown()` never called — threads leak per test run | 🟡 High |
| 50 | [Fork Without Join](50-fork-join-pool/) | `ForkJoinPoolDetector` | A forked half is never joined, so a *sorted* list comes back missing elements and a task's exception is lost | 🟡 High |
| 51 | [ForkJoin Task Blocking](51-fork-join-task-blocking/) | `ForkJoinTaskBlockingDetector` | `Thread.sleep()` inside `RecursiveTask.compute()` pins ForkJoin worker thread | 🟡 High |
| 52 | [HTTP Client Concurrency](52-http-client-concurrency/) | `HttpClientConcurrencyDetector` | `sendAsync()` whose future is discarded: the request is sent and nothing ever establishes whether it worked, on a client built per call | 🟢 Low |
| 53 | [InheritableThreadLocal Misuse](53-inheritable-thread-local/) | `InheritableThreadLocalMisuseDetector` | Thread pools inherit stale context from previous requests via `InheritableThreadLocal` | 🟡 High |
| 54 | [JDBC Connection Shared](54-jdbc-connection-shared/) | `JdbcConnectionSharedDetector` | Single `Connection` shared across threads — concurrent queries corrupt each other | 🔴 Critical |
| 55 | [Lazy Init Race](55-lazy-init-race/) | `LazyInitRaceDetector` | Unsynchronized null-check lazy init — multiple threads create separate instances | 🔴 Critical |
| 56 | [Lock Downgrade](56-lock-downgrade/) | `LockDowngradeDetector` | Write lock released before read lock acquired — gap lets another thread write in between | 🔴 Critical |
| 57 | [Lock Leak](57-lock-leak/) | `LockLeakDetector` | Exception between `lock()` and `unlock()` leaves lock permanently held | 🔴 Critical |
| 58 | [Missed Signal](58-missed-signal/) | `MissedSignalDetector` | `notify()` fired before `wait()` — signal lost, waiter blocks forever | 🔴 Critical |
| 59 | [Mutable Map Key](59-mutable-map-key/) | `MutableMapKeyDetector` | Mutable object used as `HashMap` key; mutation after insertion makes entry unreachable | 🟡 High |
| 60 | [Nested Monitor Lockout](60-nested-monitor-lockout/) | `NestedMonitorLockoutDetector` | Thread holds monitor A and calls `wait()` on B; another holds B and notifies A — circular deadlock | 🔴 Critical |
| 61 | [Notify Without Monitor](61-notify-without-monitor/) | `NotifyWithoutMonitorDetector` | `notify()` called without holding the object's monitor — `IllegalMonitorStateException` | 🔴 Critical |
| 62 | [Optimistic Read Validation](62-optimistic-read-validation/) | `OptimisticReadValidationDetector` | `StampedLock.tryOptimisticRead()` used without `validate()` — stale data returned silently | 🟡 High |
| 63 | [Parallel Stream Side-Effects](63-parallel-stream/) | `ParallelStreamDetector` | `parallelStream().forEach()` mutates shared `ArrayList` — lost updates and CME | 🔴 Critical |
| 64 | [Phaser Misuse](64-phaser-misuse/) | `PhaserDetector` | Fewer parties registered than threads that arrive — `IllegalStateException` or early termination | 🟡 High |
| 65 | [Public Lock Exposure](65-public-lock-exposure/) | `PublicLockExposureDetector` | `getLock()` exposes internal `ReentrantLock` — external callers can hold it indefinitely | 🟡 High |
| 66 | [Reentrant Lock Imbalance](66-reentrant-lock/) | `ReentrantLockDetector` | `lock()` called twice, `unlock()` once — hold count stays at 1, starving all callers | 🔴 Critical |
| 67 | [Resource Leak](67-resource-leak/) | `ResourceLeakDetector` | `InputStream` opened per call but never closed — file descriptor exhaustion | 🟡 High |
| 68 | [Scheduled Executor Leak](68-scheduled-executor/) | `ScheduledExecutorDetector` | `ScheduledExecutorService` created but `shutdown()` never called — threads accumulate | 🟡 High |
| 69 | [Scoped Value Misuse](69-scoped-value-misuse/) | `ScopedValueMisuseDetector` | Context read without null-guard outside binding scope — NPE or wrong-user processing | 🟡 High |
| 70 | [Semaphore Misuse](70-semaphore-misuse/) | `SemaphoreMisuseDetector` | `release()` not in `finally` — exception permanently drains semaphore permits | 🔴 Critical |
| 71 | [Shared Formatter](71-shared-formatter/) | `SharedFormatterDetector` | Shared `Formatter` instance concurrent calls produce garbled output | 🟡 High |
| 72 | [Shared Random](72-shared-random/) | `SharedRandomDetector` | Static `Random` shared across threads — seed contention degrades performance | 🟢 Low |
| 73 | [SimpleDateFormat Shared](73-simple-date-format/) | `SimpleDateFormatDetector` | Static `SimpleDateFormat` concurrent `format()`/`parse()` corrupts internal calendar state | 🔴 Critical |
| 74 | [Sleep In Lock](74-sleep-in-lock/) | `SleepInLockDetector` | `Thread.sleep()` inside `synchronized` block serializes all callers for the sleep duration | 🟡 High |
| 75 | [StampedLock Not Unlocked](75-stamped-lock/) | `StampedLockDetector` | `writeLock()` stamp not released in `finally` — lock permanently held, all callers block | 🔴 Critical |
| 76 | [Stateful Lambda](76-stateful-lambda/) | `StatefulLambdaDetector` | Shared `int[]` captured by lambda mutated by multiple threads — non-atomic lost updates | 🔴 Critical |
| 77 | [Stream Not Closed](77-stream-closing/) | `StreamClosingDetector` | `Stream` backed by I/O resource opened but `close()` never called — file descriptor leak | 🟡 High |
| 78 | [StringBuilder Shared](78-string-builder-shared/) | `StringBuilderDetector` | Single `StringBuilder` appended concurrently — garbled output or `ArrayIndexOutOfBoundsException` | 🔴 Critical |
| 79 | [Structured Concurrency Leak](79-structured-concurrency/) | `StructuredConcurrencyMisuseDetector` | Executor (scope) forked but `shutdown()` never called — threads and resources leak | 🟡 High |
| 80 | [SynchronizedList Iteration](80-synchronized-collection-iteration/) | `SynchronizedCollectionIterationDetector` | `synchronizedList` iterated without external lock — iterator not thread-safe | 🔴 Critical |
| 81 | [Synchronized Non-Final](81-synchronized-non-final/) | `SynchronizedNonFinalDetector` | Lock object is non-final and reassigned — two threads enter the critical section simultaneously | 🔴 Critical |
| 82 | [Synchronized On Literal](82-synchronized-on-literal/) | `SynchronizedOnLiteralDetector` | `synchronized("string-literal")` uses a JVM-global interned monitor — unintended contention | 🟡 High |
| 83 | [Thread Factory Missing](83-thread-factory/) | `ThreadFactoryDetector` | `newFixedThreadPool` without `ThreadFactory` — generic names, non-daemon, hard to debug | 🟢 Low |
| 84 | [Thread Leak](84-thread-leak/) | `ThreadLeakDetector` | New `Thread` spawned per connection, `shutdown()` never called — idle threads accumulate | 🟡 High |
| 85 | [ThreadLocal Contamination](85-thread-local-contamination/) | `ThreadLocalContaminationDetector` | `ThreadLocal.remove()` not called — pooled thread carries previous request's context | 🟡 High |
| 86 | [Thread Pool Deadlock](86-thread-pool-deadlock/) | `ThreadPoolDeadlockDetector` | Task blocks waiting for another task on the same bounded pool — pool exhaustion deadlock | 🔴 Critical |
| 87 | [Thread Starvation](87-thread-starvation/) | `ThreadStarvationDetector` | Non-fair lock held by high-priority tasks — low-priority threads never scheduled | 🟡 High |
| 88 | [Timer Misuse](88-timer-misuse/) | `TimerDetector` | `java.util.Timer` never cancelled; slow task delays all subsequent scheduled tasks | 🟢 Low |
| 89 | [Unbounded Queue](89-unbounded-queue/) | `UnboundedQueueDetector` | `newFixedThreadPool` backed by unbounded `LinkedBlockingQueue` — OOM under load | 🟡 High |
| 90 | [VT Carrier Exhaustion](90-virtual-thread-carrier-exhaustion/) | `VirtualThreadCarrierExhaustionDetector` | `synchronized` + `Thread.sleep()` in virtual thread pins carrier, exhausting the pool | 🔴 Critical |
| 91 | [VT CPU-Bound Task](91-virtual-thread-cpu-bound/) | `VirtualThreadCpuBoundTaskDetector` | CPU-intensive loop on virtual thread monopolises carrier, defeating work-stealing | 🟡 High |
| 92 | [VT Pinning](92-virtual-thread-pinning/) | `VirtualThreadPinningDetector` | `synchronized` method with I/O inside pins virtual thread carrier — defeats Loom's purpose | 🔴 Critical |
| 93 | [Volatile Array](93-volatile-array/) | `VolatileArrayDetector` | `volatile int[]` only guarantees reference visibility, not element visibility | 🟡 High |
| 94 | [Wait Without Timeout](94-wait-timeout/) | `WaitTimeoutDetector` | `wait()` with no timeout — missed `notify()` causes permanent thread block | 🔴 Critical |
| 95 | [Spurious Wakeup](95-wakeup-issues/) | `WakeupDetector` | `wait()` guarded by `if` not `while` — spurious wakeup proceeds on unmet condition | 🟡 High |
| 96 | [WeakHashMap Shared](96-weak-hashmap-shared/) | `WeakHashMapSharedDetector` | `WeakHashMap` shared across threads — GC-triggered cleanup + concurrent puts corrupt structure | 🔴 Critical |
| 97 | [WeakReference Race](97-weak-reference-race/) | `WeakReferenceRaceDetector` | `ref.get() != null` check then `ref.get().use()` — GC can clear between the two calls | 🟡 High |
| 98 | [Async Pipeline Monitor](98-async-pipeline-monitor/) | `PipelineMonitor` | Uncoordinated pipeline stages with no back-pressure — slow stages lose events under load | 🟡 High |
| 99 | [Atomic Non-Atomic Update](99-atomic-non-atomic/) | `AtomicNonAtomicUpdateDetector` | `counter.set(counter.get() + 1)` on `AtomicInteger` — non-atomic compound op loses updates | 🔴 Critical |
| 100 | [Constructor Safety](100-constructor-safety/) | `ConstructorSafetyValidator` | `this` published to registry before fields initialised — partially constructed object visible | 🔴 Critical |
| 101 | [Memory Ordering](101-memory-ordering/) | `MemoryOrderingMonitor` | Non-volatile `ready` + `value` fields — consumer may read stale values | 🟡 High |
| 102 | [Synchronizer Monitor](102-synchronizer-monitor/) | `SynchronizerMonitor` | Three synchronizers (Semaphore + Lock + Latch) chained on one path — over-synchronised | 🟢 Low |
| 103 | [Thread Pool Monitor](103-thread-pool-monitor/) | `ThreadPoolMonitor` | 2-thread pool saturated by 100 concurrent tasks — queue depth and rejection rate reported | 🟡 High |
| 104 | [Shared Stateful Crypto](104-shared-stateful-crypto/) | `SharedStatefulCryptoDetector` | A `Cipher`/`Mac`/`Signature` shared across threads — interleaved `init`/`update`/`doFinal` corrupts ciphertext or breaks MAC/signature integrity | 🔴 Critical |
| 105 | [Concurrent Map Check-Then-Act](105-concurrent-map-check-then-act/) | `NonAtomicConcurrentMapUpdateDetector` | `containsKey`-then-`put` on a `ConcurrentMap` — compound op is not atomic, concurrent callers lose updates | 🔴 Critical |
| 106 | [Shared Deflater/Inflater](106-shared-deflater/) | `SharedDeflaterDetector` | A `java.util.zip.Deflater` shared across threads — stateful native zlib stream corrupts output or crashes on `end()` mid-stream | 🔴 Critical |
| 107 | [This-Escape From Constructor](107-this-escape/) | `ThisEscapeDetector` | Constructor publishes `this` (registers a listener / starts a thread) before returning — other threads see a partially-constructed object | 🟡 High |
| 108 | [ThreadLocalRandom Misuse](108-thread-local-random-misuse/) | `ThreadLocalRandomMisuseDetector` | A cached `ThreadLocalRandom.current()` reference used from other threads — defeats per-thread isolation, biases output | 🟡 High |
| 109 | [CompletableFuture Obtrude Abuse](109-completablefuture-obtrude-abuse/) | `CompletableFutureObtrudeDetector` | `obtrudeValue`/`obtrudeException` overwrite an already-published result — downstream stages observe two different values for one future | 🟡 High |
| 110 | [Spurious Wakeup Hazard](110-spurious-wakeup-hazard/) | `SpuriousWakeupDetector` | `wait()` outside a condition-checking loop — a wakeup is not a guarantee that the condition holds | 🔴 Critical |
| 111 | [Lock Upgrade Deadlock](111-lock-upgrade-deadlock/) | `LockUpgradeDeadlockDetector` | Acquiring the write lock while holding the read lock — `ReentrantReadWriteLock` cannot upgrade, so the thread waits on itself | 🔴 Critical |
| 112 | [tryLock Misuse](112-try-lock-misuse/) | `TryLockMisuseDetector` | `unlock()` after a `tryLock()` that failed, or a `tryLock()` whose result is never checked — releases a lock this thread never held | 🔴 Critical |
| 113 | [CF Blocking Callback](113-completablefuture-blocking-callback/) | `CompletableFutureBlockingCallbackDetector` | A blocking call inside a `thenApply`/`thenAccept` callback occupies a common-pool worker for the duration | 🟡 High |
| 114 | [StableValue Misuse](114-stable-value-misuse/) | `StableValueMisuseDetector` *(standalone, JDK 25/26)* | `StableValue` (JEP 502) read before set (`NoSuchElementException`) or set twice (lost update) | 🔴 Critical |
| 115 | [StructuredTaskScope Misuse](115-structured-task-scope-misuse/) | `StructuredTaskScopeMisuseDetector` *(standalone, JDK 25/26)* | `StructuredTaskScope.open(Joiner)` (JEP 505) lifecycle broken — fork-after-join, result-before-join, owner-confinement, missing join | 🔴 Critical |
| 116 | [Gatherer Parallel Misuse](116-gatherer-parallel-misuse/) | `GathererConcurrencyMisuseDetector` *(standalone, JDK 24+)* | Stateful `Gatherer` (JEP 485) on a parallel stream with no combiner — per-thread states can't merge, results lost | 🟠 High |
| 117 | [LazyConstant Misuse](117-lazy-constant-misuse/) | `LazyConstantMisuseDetector` *(JDK 26)* | `LazyConstant` (Lazy Constants, 2nd preview) supplier returns null (NPE), re-enters itself, or runs more than once in a hand-rolled holder | 🔴 Critical |
| 118 | [Final Field Mutation](118-final-field-mutation/) | `FinalFieldMutationDetector` *(JEP 500, JDK 26)* | Reflective `Field.set` on a `final` field — warned on JDK 26, denied in a future release, voids the JMM final-field publication guarantee today | 🔴 Critical |
| 119 | [Shared KDF](119-shared-kdf/) | `SharedKdfDetector` *(JEP 510, JDK 25)* | One `javax.crypto.KDF` instance shared across threads — documented not thread-safe, silently derives wrong keys | 🟡 High |
| 120 | [Shared ByteBuffer](120-shared-byte-buffer/) | `SharedByteBufferDetector` | One `ByteBuffer` framing every thread's message — `position`/`limit`/`mark` are mutable state, so relative `put`/`get`, `flip()` and `clear()` corrupt the cursor | 🔴 Critical |
| 121 | [Shared CharsetEncoder/Decoder](121-shared-charset-coder/) | `SharedCharsetCoderDetector` | `Charset` is thread-safe, its coders are not — a cached `CharsetEncoder` garbles output or throws when two threads run its reset/encode/flush protocol | 🔴 Critical |
| 122 | [Shared Checksum](122-shared-checksum/) | `SharedChecksumDetector` | One `CRC32` accumulating across threads — produces a well-formed checksum over both payloads that matches neither, with no exception | 🔴 Critical |
| 123 | [FileChannel Position Race](123-file-channel-position-race/) | `FileChannelPositionRaceDetector` | `read(buf)`/`write(buf)` share one implicit cursor — concurrent appends land at unpredictable offsets and silently overwrite each other | 🔴 Critical |
| 124 | [Shared Iterator](124-shared-iterator/) | `SharedIteratorDetector` | One `Iterator` used as a work queue — `hasNext()`/`next()` is check-then-act, so elements are skipped, doubled, or `NoSuchElementException` fires | 🔴 Critical |
| 125 | [High-Contention Atomic](125-high-contention-atomic/) | `HighContentionAtomicDetector` | One hot `AtomicLong` — correct, but CAS-retry bound; advisory pointing at `LongAdder` for counters that are only ever totalled | 🟢 Low |
| 126 | [Shared JSON Mapper Reconfig](126-shared-json-mapper-reconfig/) | `SharedJsonMapperReconfigDetector` | Sharing the mapper is recommended; *reconfiguring* it after other threads are serializing through it drops caches mid-flight | 🔴 Critical |
| 127 | [Shared SecureRandom](127-shared-secure-random/) | `SharedSecureRandomDetector` | One `SecureRandom` behind every token — thread safety is provider-dependent, and a provider that does not synchronize can issue duplicate session tokens | 🔴 Critical |
| 128 | [Kotlin Lost Update](128-kotlin-lost-update/) | `RaceConditionDetector` | Kotlin's `var` compiles to the same non-atomic read-modify-write Java's does — `count++` in a coroutine loses updates exactly as it would on a JVM thread | 🔴 Critical |
| 129 | [Confined Arena Thread Escape](129-confined-arena-thread-escape/) | `ConfinedArenaThreadEscapeDetector` | A segment from `Arena.ofConfined()` is handed to a pool thread — `WrongThreadException` if you are lucky, a read of freed memory after the arena closes if you are not | 🔴 Critical |
| 130 | [Shared MemorySegment Race](130-shared-memory-segment-race/) | `SharedMemorySegmentRaceDetector` | `Arena.ofShared()` permits every thread to access the segment; it does not stop two of them tearing the same bytes | 🔴 Critical |
| 131 | [VarHandle Non-Atomic Update](131-var-handle-non-atomic-update/) | `VarHandleNonAtomicUpdateDetector` | `getVolatile` then `setVolatile` reads as atomic and is two operations — volatile buys visibility, never atomicity of a read-modify-write | 🔴 Critical |
| 132 | [Static Initializer Deadlock](132-static-init-deadlock/) | `StaticInitDeadlockDetector` | Two `<clinit>` blocks referencing each other deadlock on class-init locks, which are not monitors and so are invisible to `ThreadMXBean` | 🔴 Critical |
| 133 | [Flow Publisher Concurrency](133-flow-publisher-concurrency/) | `FlowPublisherConcurrencyDetector` | Reactive Streams rule 1.3 requires serial signals; a publisher fanning `onNext` out to a pool corrupts every lock-free subscriber written against it | 🔴 Critical |
| 134 | [Record Mutable Component Leak](134-record-mutable-component-leak/) | `RecordMutableComponentLeakDetector` | Records are *shallowly* immutable — a `List` component shared across threads is a live view, not the snapshot the record appears to be | 🟡 High |
| 135 | [Asserting on Findings](135-asserting-on-findings/) | *(tour, not a bug)* | `AsyncFindings` collects the structured `Violation` behind every finding, so a test can assert that a detector fired instead of substring-matching its report; also the described `awaitUntil` and `FutureCapture.requireResult` | 🟢 Low |
| 136 | [Virtual Thread Pooling](136-virtual-thread-pooling/) | `VirtualThreadPoolingDetector` | `newFixedThreadPool(4, Thread.ofVirtual().factory())` keeps the cap, the queue and the recycled workers — JEP 444's "never pool virtual threads", verbatim | 🟡 High |
| 137 | [Platform Thread-Per-Task](137-platform-thread-per-task/) | `PlatformThreadPerTaskDetector` | `new Thread(task).start()` per webhook costs an OS thread and ~1 MB of stack each — survives the unit test, collapses at the production burst | 🟡 High |
| 138 | [Shared SplittableRandom](138-shared-splittable-random/) | `SharedSplittableRandomDetector` | `SplittableRandom` shared across workers interleaves a plain read-modify-write — duplicated values and broken statistics with no exception to notice | 🟡 High |
| 139 | [CompletableFuture Completion Race](139-cf-completion-race/) | `CompletableFutureCompletionRaceDetector` | Several threads `complete()` one future; the losers get `false` back and drop their value — or their exception, so an outage reads as a success | 🔴 High |
| 140 | [CompletableFuture Cancellation Propagation](140-cf-cancellation-propagation/) | `CompletableFutureCancellationPropagationDetector` | `cancel()` completes one future and stops there — the upstream export writes all 50,000 rows anyway, and `cancel(true)` never interrupts this type | 🔴 High |
| 141 | [CompletableFuture Combinator Misuse](141-cf-combinator-misuse/) | `CompletableFutureCombinatorMisuseDetector` | `allOf`/`anyOf` are constructors, not barriers — the dropped result lets the caller proceed mid-write, and `anyOf` losers fail with no handler to reach | 🔴 High |
| 142 | [Lambda Captured-State Lost Update](142-lambda-lost-update/) | `LambdaLostUpdateDetector` | `hits[0] = hits[0] + 1` from two threads that read the same value — a proven lost update, silent under an atomic or a consistently held monitor | 🔴 High |
| 143 | [Virtual Thread Resource Saturation](143-vthread-resource-saturation/) | `VirtualThreadResourceSaturationDetector` | The fixed pool was accidental admission control; a thread per task removes it and ten thousand callers queue on a ten-connection pool | 🔴 High |
| 144 | [Virtual Thread Monitor Serialization](144-vthread-monitor-serialization/) | `VirtualThreadMonitorSerializationDetector` | JEP 491 removed the pinning and left the bottleneck — `synchronized` still admits one thread at a time, and nothing bounds how many arrive | 🔴 High |
| 145 | [ThreadLocal Cache Degradation](145-threadlocal-cache-degradation/) | `ThreadLocalCacheDegradationDetector` | `ThreadLocal<SimpleDateFormat>` was a cache because the pool bounded it; per task it allocates one formatter per request and the code never changed | 🟡 Medium |
| 146 | [Scope Joiner Misuse](146-scope-joiner-misuse/) | `ScopeJoinerMisuseDetector` | `onComplete` runs on the subtask threads and JEP 525's `onTimeout()` on the owner — the partial result is copied out of a list another thread is still appending to | 🟠 High |
| 147 | [Scope Configuration Misuse](147-scope-configuration-misuse/) | `ScopeConfigurationMisuseDetector` | `Configuration` is immutable, so a lambda that drops what `withTimeout` returned applies no deadline at all and the scope waits on its slowest subtask forever | 🟠 High |
| 148 | [Scope Result Escape](148-scope-result-escape/) | `ScopeResultEscapeDetector` | JDK 26 returns a `List` where JDK 25 returned a `Stream`; a `List` stores happily in a field, so the subtask handles are read after the scope that guaranteed them closed | 🔴 High |
| 149 | [Lazy Collection Misuse](149-lazy-collection-misuse/) | `LazyCollectionMisuseDetector` | `List.ofLazy` elements compute on whichever thread asks first, so a mapping function reading its own collection deadlocks two threads the JDK's cycle check never sees | 🔴 High |

> Examples 114–119 target JDK 24–26 concurrency features. The detectors work off recorded
> `String`-key + `Thread` events, so they compile and run on the Java 21 baseline while
> modeling APIs that only exist on newer JDKs. As of 1.7.0 they are wired into the
> `@AsyncTest` pipeline (`DetectorType.STABLE_VALUE_MISUSE` … `SHARED_KDF`) and can also be
> instantiated standalone. They depend on the in-progress build, so install the parent to
> `mavenLocal` first (`mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true`).

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

## Phase 11: Thread-Safety of Additional Types & Patterns (New in 0.10.0)

Five new detectors for JDK types that look thread-safe but corrupt state under unsynchronized
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

## JDK 25/26 Examples

Examples **114–119** target concurrency features introduced or finalized in JDK 24–26.
Each detector is implemented against `String` keys + `Thread` (or `Object` instances), so it
compiles and runs on the Java 21 baseline while modeling APIs that exist only on JDK 24/25/26.
All of them are wired into the `@AsyncTest` pipeline via `DetectorType` constants and can
also be instantiated standalone — instantiate, record events, call `analyze()`.

### 114 — StableValue Misuse (JEP 502)
**What**: `StableValue<T>` is a deferred-immutable holder, settable at most once and then
constant-folded by the JVM. Detects read-before-set (`NoSuchElementException`), double-set
(lost update / `IllegalStateException`), and reentrant `orElseSet` suppliers.

**Detect**:
```java
var d = new StableValueMisuseDetector();
d.recordRead("CONFIG", Thread.currentThread());   // before any set → flagged
d.recordSet("CONFIG", Thread.currentThread());
d.recordSet("CONFIG", Thread.currentThread());    // double set → flagged
assertTrue(d.analyze().hasIssues());
```

### 115 — StructuredTaskScope Misuse (JEP 505)
**What**: The JDK 25 `StructuredTaskScope.open(Joiner)` API enforces
`open → fork* → join → get* → close`. Detects fork-after-join, `Subtask.get()` before join,
owner-confinement violations (`WrongThreadException`), and close-without-join.

**Detect**:
```java
var d = new StructuredTaskScopeMisuseDetector();
Thread owner = Thread.currentThread();
d.recordScopeOpened("s", owner);
d.recordFork("s", "a", owner);
d.recordJoin("s", owner);
d.recordFork("s", "late", owner);     // fork after join → flagged
assertTrue(d.analyze().hasIssues());
```

### 116 — Gatherer Parallel Misuse (JEP 485)
**What**: A stateful `Gatherer` on a parallel stream needs a combiner to merge per-thread
states. Detects a stateful gatherer with no combiner running on more than one thread (lost
results) and concurrent-integrator shared-state races.

**Detect**:
```java
var d = new GathererConcurrencyMisuseDetector();
d.registerGatherer("running", /*hasCombiner*/ false, /*parallel*/ true);
// integrator: d.recordIntegrate("running", Thread.currentThread());
assertTrue(d.analyze().hasIssues());
```

### 117 — LazyConstant Misuse (JDK 26, Lazy Constants 2nd preview)
**What**: `LazyConstant.of(supplier)` — the renamed, simplified `StableValue` — computes at
most once on first `get()` and rejects null. Detects reentrant suppliers
(`IllegalStateException`), null-producing suppliers (NPE on JDK 26), hand-rolled holders
whose computation runs more than once, non-deterministic suppliers, and compute convoys.

**Detect**:
```java
var d = new LazyConstantMisuseDetector();
d.recordComputeStart("CONFIG", Thread.currentThread());
d.recordComputeEnd("CONFIG", Thread.currentThread(), null);   // null result → flagged
assertTrue(d.analyze().hasIssues());
```

### 118 — Final Field Mutation (JEP 500, JDK 26)
**What**: JDK 26 warns on reflective `Field.set` of `final` fields and a future release
denies it. The JMM's final-field publication guarantee only covers constructor writes —
reflective writers race every reader, who may see the stale value forever. Detects any
mutation (HIGH), escalating when foreign threads read the field or multiple threads write
it (CRITICAL).

**Detect**:
```java
var d = new FinalFieldMutationDetector();
d.recordMutation("Config.MAX_RETRIES", Thread.currentThread());   // flagged
assertTrue(d.analyze().hasIssues());
```

### 119 — Shared KDF (JEP 510, JDK 25)
**What**: `javax.crypto.KDF` is documented not thread-safe; concurrent
`deriveKey()`/`deriveData()` on one instance can interleave provider state and silently
derive wrong keys. Detects any KDF instance accessed from more than one thread.

**Detect**:
```java
var d = new SharedKdfDetector();
d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", threadA);
d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", threadB);   // 2 threads → flagged
assertTrue(d.analyze().hasIssues());
```

---

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with barriers exposes race conditions, visibility issues, and unhandled exceptions
3. **Always handle async exceptions**: Use `.exceptionally()`, `.handle()`, or equivalent
4. **Use volatile for shared flags**: Any field read/written by multiple threads needs `volatile` or `Atomic*` types
5. **Test under concurrent load**: What works sequentially often fails under real concurrent access
