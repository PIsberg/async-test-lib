package se.deversity.asynctest;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an asynchronous stress test.
 * The test will be executed concurrently across multiple threads
 * using a CyclicBarrier to maximize the chance of race conditions.
 * 
 * Supports detection of:
 * - Deadlocks (with thread dump analysis)
 * - Visibility issues (missing volatile keywords)
 * - Livelocks and thread starvation
 * - Virtual thread pinning issues (Java 21+)
 */
@AIContract(reason = "Public annotation API used directly in user test methods. Attribute names, types, and defaults are part of the stable public API — any change is a breaking change for all consumers.")
@AIPublicAPI
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(se.deversity.asynctest.extension.AsyncTestExtension.class)
public @interface AsyncTest {

    /**
     * Number of threads to run concurrently per invocation.
     * Each thread will execute the test method once per invocation round.
     */
    int threads() default 10;

    /**
     * Optional schedule matrix: when non-empty, the test runs once per entry,
     * each run using that entry as the thread count. The {@link #threads()}
     * value is ignored for runs that use this matrix.
     *
     * <p>Bug-finding sensitivity is often thread-count-dependent — a race that
     * misses at 4 threads can surface reliably at 32, and vice versa. Use this
     * to sweep a range cheaply.
     *
     * <p>Example:
     * <pre>{@code
     * @AsyncTest(threadCounts = {1, 2, 4, 8, 16, 32, 64})
     * void racy_under_contention() { ... }
     * }</pre>
     *
     * <p>Default empty array means "use {@link #threads()}" (legacy behavior).
     *
     * @since 1.0.0
     */
    int[] threadCounts() default {};

    /**
     * Number of times the entire concurrent execution is repeated.
     */
    int invocations() default 100;

    /**
     * Whether to use Virtual Threads (Project Loom) instead of standard platform threads.
     * Requires Java 21+.
     */
    boolean useVirtualThreads() default true;

    /**
     * Maximum time to wait for the entire test (all threads and invocations) to complete.
     * If exceeded, a deadlock is assumed and a JVM Thread dump will be triggered.
     * Default is 5000ms.
     */
    long timeoutMs() default 5000;

    /**
     * Enable deadlock detection with detailed lock analysis.
     * When test times out, provides information about which threads hold which locks.
     */
    boolean detectDeadlocks() default true;

    /**
     * Enable visibility monitoring (stale memory detection).
     * Detects missing volatile keywords and insufficient synchronization.
     * This adds overhead, so only enable when testing code with potential visibility issues.
     */
    boolean detectVisibility() default false;

    /**
     * Enable livelock and starvation detection.
     * Monitors for threads that change state rapidly without making progress,
     * or threads that never get CPU time.
     */
    boolean detectLivelocks() default false;

    /**
     * Virtual thread stress test mode. When enabled, uses aggressive thread counts
     * to detect thread-pinning issues (e.g., synchronized blocks pinning virtual threads).
     * Only applicable when useVirtualThreads=true.
     * 
     * Values:
     * - "OFF" (default): normal testing
     * - "LOW": 100 threads
     * - "MEDIUM": 1,000 threads
     * - "HIGH": 10,000 threads
     * - "EXTREME": 100,000+ threads (may require heap size adjustment)
     */
    String virtualThreadStressMode() default "OFF";

    /**
     * Enable ALL detectors in one shot.
     * When {@code true}, every individual {@code detect*} / {@code validate*} / {@code monitor*}
     * flag is treated as enabled, regardless of its own default value.
     * Individual flags can still be set to {@code false} to opt out of specific detectors.
     * <p><strong>Default is {@code true}</strong> — {@code @AsyncTest} alone enables all detectors.
     * <p>Example: {@code @AsyncTest} — all detectors enabled automatically.
     * <p>Example: {@code @AsyncTest(detectAll = false, detectDeadlocks = true)} — only deadlock detection.
     */
    boolean detectAll() default true;

    /**
     * Curated detector bundle. Overrides {@link #detectAll()} and the per-flag
     * boolean attributes when set to anything other than {@link Preset#ALL}.
     *
     * <ul>
     *   <li>{@link Preset#ALL} — every detector (default; equivalent to legacy {@code detectAll = true}).</li>
     *   <li>{@link Preset#ESSENTIALS} — ~12 high-signal detectors for everyday CI.</li>
     *   <li>{@link Preset#STRICT} — same as ALL, named explicitly.</li>
     *   <li>{@link Preset#CI_FAST} — minimal set for pull-request gates.</li>
     *   <li>{@link Preset#NONE} — disable all detectors; concurrent execution only.</li>
     * </ul>
     *
     * <p>{@link #excludes()} still applies on top of the preset, letting you trim
     * one or two detectors from a curated bundle.
     *
     * @since 1.0.0
     */
    Preset preset() default Preset.ALL;

    /**
     * Replay seed for deterministic re-runs.
     *
     * <p>The runner exposes a {@code long} seed per invocation via
     * {@link AsyncTestContext#replaySeed()}. When {@link #replaySeed()} is
     * {@code 0} (default), each invocation gets a fresh random seed and the
     * value is logged on test failure so you can plug it back in. When set
     * explicitly, every invocation uses that exact seed.
     *
     * <p>This does <em>not</em> make thread scheduling deterministic — that
     * would require JVM-level instrumentation — but it gives any RNG-driven
     * input in your test body (sleep jitter, randomised payloads, choice of
     * worker behaviour) a stable starting point so a failure caught once can
     * be reproduced.
     *
     * <p>Usage pattern:
     * <pre>{@code
     * @AsyncTest
     * void flaky_race() {
     *     long seed = AsyncTestContext.replaySeed();
     *     var rng = new Random(seed);
     *     // ... use rng for any randomised choices in the body
     * }
     * }</pre>
     *
     * @since 1.0.0
     */
    long replaySeed() default 0L;

    /**
     * Specific detectors to exclude when {@code detectAll = true}.
     * Use {@link DetectorType} to specify which detectors to skip.
     * <p>Example: {@code @AsyncTest(detectAll = true, excludes = {DetectorType.BUSY_WAITING})}
     */
    DetectorType[] excludes() default {};

    // ============= Phase 2: Advanced Detectors =============

    /**
     * Enable false sharing detection.
     * Detects when multiple threads access adjacent memory locations in the same cache line,
     * causing excessive cache coherency traffic.
     */
    boolean detectFalseSharing() default true;

    /**
     * Enable wait/notify issue detection.
     * Detects spurious wakeups, lost notifications, and improper wait/notify coordination.
     */
    boolean detectWakeupIssues() default true;

    /**
     * Enable constructor safety validation.
     * Verifies objects are fully constructed before being shared across threads.
     */
    boolean validateConstructorSafety() default true;

    /**
     * Enable ABA problem detection.
     * Detects ABA scenarios in atomic operations and CAS loops that can cause data corruption.
     */
    boolean detectABAProblem() default true;

    /**
     * Enable lock order validation.
     * Detects inconsistent lock orderings across threads that can cause deadlocks.
     */
    boolean validateLockOrder() default true;

    /**
     * Enable synchronizer monitoring (barriers, phasers, latches).
     * Detects synchronization issues like incomplete barrier advances.
     */
    boolean monitorSynchronizers() default true;

    /**
     * Enable thread pool health monitoring.
     * Detects queue saturation, task rejection, worker starvation.
     */
    boolean monitorThreadPool() default true;

    /**
     * Enable memory ordering violation detection.
     * Detects compiler/CPU reordering that causes incorrect synchronization.
     */
    boolean detectMemoryOrderingViolations() default true;

    /**
     * Enable async pipeline monitoring.
     * Detects signal loss, missing events, and processing failures in event pipelines.
     */
    boolean monitorAsyncPipeline() default true;

    /**
     * Enable read-write lock fairness monitoring.
     * Detects writer starvation and unfair lock distributions.
     */
    boolean monitorReadWriteLockFairness() default true;

    /**
     * Enable race condition detection.
     * Detects concurrent field access patterns and unsynchronized mutations.
     */
    boolean detectRaceConditions() default true;

    /**
     * Enable ThreadLocal leak detection.
     * Detects ThreadLocal values not cleaned up, causing memory leaks in thread pools.
     */
    boolean detectThreadLocalLeaks() default true;

    /**
     * Enable busy-waiting detection.
     * Detects CPU-intensive spin loops and polling patterns without proper synchronization.
     */
    boolean detectBusyWaiting() default true;

    /**
     * Enable atomicity violation detection.
     * Detects check-then-act patterns and compound operations that aren't properly synchronized.
     */
    boolean detectAtomicityViolations() default true;

    /**
     * Enable interrupt handling monitoring.
     * Detects caught but ignored InterruptException and improper thread cancellation handling.
     */
    boolean detectInterruptMishandling() default true;

    // ============= Phase 2: Additional Monitors =============

    /**
     * Enable semaphore misuse monitoring.
     * Detects permit leaks, over-release, and unreleased permits at completion.
     */
    boolean monitorSemaphore() default true;

    /**
     * Enable CompletableFuture exception monitoring.
     * Detects unhandled exceptions, missing handlers, and swallowed exceptions in async chains.
     */
    boolean detectCompletableFutureExceptions() default true;

    /**
     * Enable CompletableFuture completion leak monitoring.
     * Detects CompletableFutures created but never completed (completable future leaks).
     * @since 1.2.0
     */
    boolean detectCompletableFutureCompletionLeaks() default true;

    /**
     * Enable virtual thread pinning detection.
     * Detects virtual threads pinned to carrier threads by synchronized blocks or native calls.
     * Requires Java 21+ with virtual thread support.
     * @since 1.2.0
     */
    boolean detectVirtualThreadPinning() default true;

    /**
     * Enable thread pool deadlock detection.
     * Detects tasks submitting nested tasks to the same pool, which can cause deadlock.
     * @since 1.2.0
     */
    boolean detectThreadPoolDeadlocks() default true;

    /**
     * Enable concurrent modification detection.
     * Detects collection modifications during iteration and concurrent mutations.
     */
    boolean detectConcurrentModifications() default true;

    /**
     * Enable lock leak detection.
     * Detects locks acquired but never released and excessive hold times.
     */
    boolean detectLockLeaks() default true;

    /**
     * Enable shared Random detection.
     * Detects concurrent access to non-thread-safe Random instances.
     */
    boolean detectSharedRandom() default true;

    /**
     * Enable BlockingQueue misuse detection.
     * Detects silent failures, queue saturation, and producer/consumer imbalance.
     */
    boolean detectBlockingQueueIssues() default true;

    /**
     * Enable Condition variable misuse detection.
     * Detects lost signals, stuck waiters, and missing signals.
     */
    boolean detectConditionVariableIssues() default true;

    /**
     * Enable SimpleDateFormat misuse detection.
     * Detects concurrent access to non-thread-safe date formatters.
     */
    boolean detectSimpleDateFormatIssues() default true;

    /**
     * Enable parallel stream misuse detection.
     * Detects stateful lambdas, non-thread-safe collectors, and side effects.
     */
    boolean detectParallelStreamIssues() default true;

    /**
     * Enable resource leak detection.
     * Detects AutoCloseable resources not properly closed.
     */
    boolean detectResourceLeaks() default true;

    // ============= Phase 2: Additional Concurrency Detectors =============

    /**
     * Enable CountDownLatch misuse detection.
     * Detects latch timeout, missing countDown, and extra countDown calls.
     */
    boolean detectCountDownLatchIssues() default true;

    /**
     * Enable CyclicBarrier misuse detection.
     * Detects barrier timeout, broken barriers, and missing participants.
     */
    boolean detectCyclicBarrierIssues() default true;

    /**
     * Enable ReentrantLock issue detection.
     * Detects lock starvation, unfair acquisition, and lock timeouts.
     */
    boolean detectReentrantLockIssues() default true;

    /**
     * Enable volatile array issue detection.
     * Detects multi-thread access to volatile array elements (which are not volatile).
     */
    boolean detectVolatileArrayIssues() default true;

    /**
     * Enable broken double-checked locking detection.
     * Detects DCL patterns without volatile keyword.
     */
    boolean detectDoubleCheckedLocking() default true;

    /**
     * Enable wait timeout detection.
     * Detects wait() calls without timeout (potential deadlock).
     */
    boolean detectWaitTimeout() default true;

    /**
     * Enable lock contention detection.
     * Detects monitors where a high proportion of acquire attempts are blocked,
     * indicating a performance-degrading contention hotspot.
     */
    boolean detectLockContention() default true;

    /**
     * Enable synchronized-on-non-final detection.
     * Detects the anti-pattern of synchronizing on a field that is not declared
     * {@code final} and may be reassigned, breaking mutual exclusion guarantees.
     */
    boolean detectSynchronizedNonFinal() default true;

    /**
     * Enable missed-signal detection.
     * Detects {@code notify()} and {@code notifyAll()} calls made when no thread
     * is waiting on the condition, causing the signal to be silently lost.
     */
    boolean detectMissedSignals() default true;

    /**
     * Enable lazy-initialization race detection.
     * Detects fields that are initialized by multiple concurrent threads because
     * the null-guard is not properly synchronized or the field is not volatile.
     */
    boolean detectLazyInitRace() default true;

    // ============= Phase 2: Advanced Concurrency Utilities =============

    /**
     * Enable Phaser misuse detection.
     * Detects missing arrive() calls, timeouts, and termination issues.
     */
    boolean detectPhaserIssues() default true;

    /**
     * Enable StampedLock issue detection.
     * Detects unvalidated optimistic reads and stamp release issues.
     */
    boolean detectStampedLockIssues() default true;

    /**
     * Enable Exchanger misuse detection.
     * Detects exchange timeouts and missing partners.
     */
    boolean detectExchangerIssues() default true;

    /**
     * Enable ScheduledExecutorService issue detection.
     * Detects missing shutdown, long-running tasks, and exceptions.
     */
    boolean detectScheduledExecutorIssues() default true;

    /**
     * Enable ForkJoinPool issue detection.
     * Detects fork without join and task exceptions.
     */
    boolean detectForkJoinPoolIssues() default true;

    /**
     * Enable ThreadFactory issue detection.
     * Detects missing exception handlers and poor thread naming.
     */
    boolean detectThreadFactoryIssues() default true;

    // ============= Phase 4: Infrastructure & Resource Management =============

    /**
     * Enable thread leak detection.
     * Detects threads created but never terminated, leading to resource exhaustion.
     */
    boolean detectThreadLeaks() default true;

    /**
     * Enable sleep-in-lock detection.
     * Detects Thread.sleep() calls while holding locks.
     */
    boolean detectSleepInLock() default true;

    /**
     * Enable unbounded queue detection.
     * Detects BlockingQueue instances without capacity bounds.
     */
    boolean detectUnboundedQueue() default true;

    /**
     * Enable thread starvation detection.
     * Detects tasks waiting excessively long before execution.
     */
    boolean detectThreadStarvation() default true;

    // ============= Phase 5: Thread-Safety of Common Types =============

    /**
     * Enable {@code java.util.Calendar} misuse detection.
     * Detects shared Calendar instances accessed by multiple threads (not thread-safe).
     */
    boolean detectCalendarIssues() default true;

    /**
     * Enable non-thread-safe collection sharing detection.
     * Detects ArrayList, HashMap, HashSet, LinkedList, etc. accessed concurrently
     * without synchronization.
     */
    boolean detectSharedCollections() default true;

    /**
     * Enable {@code java.util.Timer} misuse detection.
     * Detects timer thread failures (uncaught exceptions kill all tasks) and
     * long-running tasks that starve subsequent tasks.
     */
    boolean detectTimerIssues() default true;

    /**
     * Enable Copy-on-Write collection performance detection.
     * Detects CopyOnWriteArrayList / CopyOnWriteArraySet used in write-heavy scenarios
     * where the O(n) copy-per-write overhead is significant.
     */
    boolean detectCopyOnWriteCollectionIssues() default true;

    /**
     * Enable {@code StringBuilder} sharing detection.
     * Detects StringBuilder instances mutated by multiple threads (not thread-safe).
     */
    boolean detectStringBuilderIssues() default true;

    // ============= Phase 6: Virtual Thread Concurrency (Java 21+) =============

    /**
     * Enable Structured Concurrency misuse detection (Java 21+).
     * Detects unclosed {@code StructuredTaskScope}, skipped {@code join()} calls,
     * subtask results accessed before {@code join()}, and empty scopes.
     * @since 0.7.0
     */
    boolean detectStructuredConcurrencyIssues() default true;

    /**
     * Enable virtual thread ThreadLocal context leak detection (Java 21+).
     * Detects {@code ThreadLocal} values set in virtual threads but never removed,
     * {@code InheritableThreadLocal} misuse inside virtual threads, and excessive
     * per-thread ThreadLocal usage (prefer {@code ScopedValue}).
     * @since 0.7.0
     */
    boolean detectVirtualThreadContextLeaks() default true;

    /**
     * Enable {@code ScopedValue} misuse detection (Java 21+).
     * Detects {@code get()} calls outside an active binding (will throw
     * {@code NoSuchElementException} at runtime), unintentional re-binding in
     * nested scopes, and excessive simultaneous binding counts.
     * @since 0.7.0
     */
    boolean detectScopedValueMisuse() default true;

    /**
     * Enable CPU-bound task detection on virtual threads (Java 21+).
     * Detects virtual threads running long-duration computation without yielding,
     * which monopolizes carrier threads and reduces scalability.
     * @since 0.7.0
     */
    boolean detectVirtualThreadCpuBoundTasks() default true;

    /**
     * Enable virtual thread carrier exhaustion detection (Java 21+).
     * Detects scenarios where concurrently blocked virtual threads approach or
     * exceed the number of available carrier platform threads, potentially causing
     * starvation even without a classic deadlock.
     * @since 0.7.0
     */
    boolean detectVirtualThreadCarrierExhaustion() default true;

    // ============= Phase 7: High-Level Concurrency Patterns =============

    /**
     * Enable HTTP client concurrency issue detection (Java 11+).
     * Detects unclosed HTTP responses, connection pool exhaustion,
     * concurrent access to shared HttpClient instances, and requests
     * initiated but never awaited/completed.
     * @since 0.7.0
     */
    boolean detectHttpClientIssues() default true;

    /**
     * Enable I/O stream closing detection.
     * Detects InputStream/OutputStream/Reader/Writer instances that are
     * opened but never closed, streams closed in different threads,
     * and too many concurrently open streams (resource exhaustion risk).
     * @since 0.7.0
     */
    boolean detectStreamClosing() default true;

    /**
     * Enable cache concurrency issue detection.
     * Detects HashMap/LinkedHashMap used as cache without synchronization,
     * concurrent read/write on non-thread-safe caches, iteration during
     * modification, and cache stampede scenarios.
     * @since 0.7.0
     */
    boolean detectCacheConcurrency() default true;

    /**
     * Enable CompletableFuture chain issue detection.
     * Detects missing .exceptionally()/.handle() in async chains,
     * CompletableFuture created but never joined, and chained operations
     * without proper exception handling.
     * @since 0.7.0
     */
    boolean detectCompletableFutureChainIssues() default true;

    // ============= Benchmarking =============

    /**
     * Enable benchmarking for this test method.
     * When true, execution times are recorded and compared against baselines.
     */
    boolean enableBenchmarking() default false;

    /**
     * Regression threshold percentage.
     * If execution time increases by more than this percentage compared to baseline,
     * a regression is detected.
     * Default is 20% (0.2 = 20%).
     */
    double benchmarkRegressionThreshold() default 0.2;

    /**
     * Fail the test on benchmark regression.
     * If true, a regression exceeding the threshold will cause test failure.
     * If false, only a warning is logged.
     */
    boolean failOnBenchmarkRegression() default false;

    // ============= Phase 8: Lifecycle & Structural Correctness =============

    /**
     * Enable ExecutorService lifecycle detection.
     * Detects executors that have tasks submitted but are never shut down, or are shut down
     * without a subsequent {@code awaitTermination()} call.
     * @since 1.3.0
     */
    boolean detectExecutorShutdown() default true;

    /**
     * Enable mutable map key detection.
     * Detects objects used as {@code HashMap}/{@code HashSet} keys that are mutated
     * (including hashCode-changing mutations) after insertion.
     * @since 1.3.0
     */
    boolean detectMutableMapKeys() default true;

    /**
     * Enable nested monitor lockout detection.
     * Detects threads that attempt blocking operations ({@code wait()}, {@code Future.get()},
     * {@code Lock.lock()}) while holding a monitor on a different object.
     * @since 1.3.0
     */
    boolean detectNestedMonitorLockout() default true;

    /**
     * Enable lock downgrade/upgrade detection.
     * Detects illegal read-to-write upgrade attempts on {@link java.util.concurrent.locks.ReentrantReadWriteLock},
     * which deadlock immediately because the upgrade is not supported.
     * @since 1.3.0
     */
    boolean detectLockDowngrade() default true;

    /**
     * Enable {@link InheritableThreadLocal} misuse detection.
     * Detects {@code InheritableThreadLocal} values read or written in thread-pool threads,
     * where inheritance happens at thread-creation time rather than task-submission time,
     * causing stale or cross-task context contamination.
     * @since 1.3.0
     */
    boolean detectInheritableThreadLocalMisuse() default true;

    // ============= Phase 9: Repository & Environment State =============

    /**
     * Enable uncommitted changes detection.
     * Detects untracked or uncommitted files in the Git repository when the test completes.
     * Helps ensure tests are run against a clean and reproducible repository state.
     * @since 1.4.0
     */
    boolean detectUncommittedChanges() default true;

    // ============= Phase 10: API Traps & Subtle Concurrency Bugs =============

    /**
     * Enable ThreadLocal cross-task contamination detection.
     * Detects ThreadLocal values set in one task that are read by the next task on the same
     * pooled thread without an intervening {@code remove()} or {@code set()}.
     * @since 1.5.0
     */
    boolean detectThreadLocalContamination() default true;

    /**
     * Enable non-atomic Atomic* update detection.
     * Detects {@code get()} followed by {@code set()} on {@link java.util.concurrent.atomic.AtomicInteger},
     * {@link java.util.concurrent.atomic.AtomicLong}, etc. without {@code compareAndSet()}.
     * @since 1.5.0
     */
    boolean detectAtomicNonAtomicUpdates() default true;

    /**
     * Enable synchronized-collection iteration detection.
     * Detects iteration over {@link java.util.Collections#synchronizedList} /
     * {@code synchronizedMap} / {@code synchronizedSet} wrappers without holding the
     * wrapper's intrinsic lock.
     * @since 1.5.0
     */
    boolean detectSynchronizedCollectionIteration() default true;

    /**
     * Enable shared formatter detection.
     * Detects {@link java.util.Formatter}, {@link java.io.PrintWriter}, and
     * {@link java.io.PrintStream} instances accessed from multiple threads without
     * external synchronization.
     * @since 1.5.0
     */
    boolean detectSharedFormatter() default true;

    /**
     * Enable ConcurrentHashMap compute recursion detection.
     * Detects recursive {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} /
     * {@code compute} / {@code merge} calls on the same map and key from the same thread,
     * causing an infinite loop (Java 8) or {@link IllegalStateException} (Java 9+).
     * @since 1.5.0
     */
    boolean detectConcurrentMapComputeRecursion() default true;

    /**
     * Enable synchronized-on-literal detection.
     * Detects {@code synchronized} blocks on interned {@link String} literals or JVM-cached
     * {@link Integer} / {@link Long} values (range [-128, 127]) — monitors shared JVM-wide.
     * @since 1.5.0
     */
    boolean detectSynchronizedOnLiteral() default true;

    /**
     * Enable public lock exposure detection.
     * Detects classes that use {@code synchronized(this)} while {@code this} is publicly
     * accessible, exposing the internal lock to external callers.
     * @since 1.5.0
     */
    boolean detectPublicLockExposure() default true;

    /**
     * Enable ForkJoinTask blocking detection.
     * Detects blocking calls ({@link Thread#sleep}, {@link Object#wait}, {@code Future.get()},
     * blocking I/O) inside a {@link java.util.concurrent.ForkJoinTask} body, which starves
     * {@link java.util.concurrent.ForkJoinPool} carrier threads.
     * @since 1.5.0
     */
    boolean detectForkJoinTaskBlocking() default true;

    /**
     * Enable StampedLock optimistic read validation detection.
     * Detects data accessed after {@link java.util.concurrent.locks.StampedLock#tryOptimisticRead()}
     * without calling {@code validate(stamp)}, or data used after a failed validation.
     * @since 1.5.0
     */
    boolean detectOptimisticReadValidation() default true;

    /**
     * Enable CompletableFuture common-pool blocking detection.
     * Detects blocking operations inside {@link java.util.concurrent.CompletableFuture} stages
     * submitted to the common {@link java.util.concurrent.ForkJoinPool} without a dedicated executor.
     * @since 1.5.0
     */
    boolean detectCFCommonPoolBlocking() default true;

    // ============= Phase 11: Thread-Safety of Additional Types & Patterns =============

    /**
     * Enable shared Matcher detection.
     * Detects {@link java.util.regex.Matcher} instances accessed concurrently from multiple
     * threads. {@code Pattern} is thread-safe but {@code Matcher} holds mutable match state.
     * @since 0.9.0
     */
    boolean detectSharedMatcher() default true;

    /**
     * Enable shared DecimalFormat/NumberFormat detection.
     * Detects {@link java.text.DecimalFormat} and {@link java.text.NumberFormat} instances
     * accessed concurrently; neither is thread-safe.
     * @since 0.9.0
     */
    boolean detectSharedDecimalFormat() default true;

    /**
     * Enable WeakReference/SoftReference race detection.
     * Detects {@code get()} results used without null-checking, and references whose referent
     * was collected mid-test — leaving some threads with null while others had non-null.
     * @since 0.9.0
     */
    boolean detectWeakReferenceRace() default true;

    /**
     * Enable stateful-lambda detection.
     * Detects lambda / {@link Runnable} / {@link java.util.concurrent.Callable} instances
     * that capture mutable state and are executed concurrently from multiple threads.
     * @since 0.9.0
     */
    boolean detectStatefulLambda() default true;

    /**
     * Enable shared MessageDigest detection.
     * Detects {@link java.security.MessageDigest} instances accessed concurrently;
     * {@code MessageDigest} is not thread-safe and concurrent {@code update()}/{@code digest()}
     * calls silently corrupt the hash state.
     * @since 0.9.0
     */
    boolean detectSharedMessageDigest() default true;

    // ============= Phase 12: Operational & Hygiene Concurrency Issues =============

    /**
     * Enable interrupt-swallowing detection.
     * Detects {@code catch (InterruptedException)} blocks that neither rethrow the exception
     * nor call {@code Thread.currentThread().interrupt()}, permanently suppressing the
     * cooperative-cancellation signal.
     * @since 0.10.0
     */
    boolean detectInterruptSwallowing() default true;

    /**
     * Enable MDC context-leak detection.
     * Detects SLF4J MDC entries that are not cleared at task end, causing key/value leakage
     * to the next task run on a reused pooled thread.
     * @since 0.10.0
     */
    boolean detectMdcContextLeak() default true;

    /**
     * Enable system-property mutation detection.
     * Detects concurrent {@link System#setProperty} or {@link System#clearProperty} calls
     * during the test run, which cause non-deterministic configuration and test pollution.
     * @since 0.10.0
     */
    boolean detectSystemPropertyMutation() default true;

    /**
     * Enable ignored-Future detection.
     * Detects {@link java.util.concurrent.Future} instances returned from
     * {@code submit()} that are never inspected, causing exceptions from failed tasks
     * to be silently swallowed.
     * @since 0.10.0
     */
    boolean detectFutureIgnored() default true;

    /**
     * Enable explicit-GC detection.
     * Detects {@link System#gc()} or {@link Runtime#gc()} invocations during concurrent
     * execution, which trigger unpredictable stop-the-world pauses that corrupt timing
     * measurements and concurrency tests.
     * @since 0.10.0
     */
    boolean detectExplicitGc() default true;

    /**
     * Enable deprecated-Thread-API detection.
     * Detects calls to {@code Thread.stop()}, {@code Thread.suspend()},
     * {@code Thread.resume()}, {@code Thread.destroy()}, or
     * {@code Thread.countStackFrames()}, which are unsafe and removed/deprecated in
     * Java 20+.
     * @since 0.10.0
     */
    boolean detectDeprecatedThreadApi() default true;

    /**
     * Enable shared-XML-parser detection.
     * Detects {@link javax.xml.parsers.DocumentBuilder}, {@link javax.xml.parsers.SAXParser},
     * {@link javax.xml.transform.Transformer}, or {@link javax.xml.xpath.XPath} instances
     * accessed concurrently from multiple threads; all are non-thread-safe.
     * @since 0.10.0
     */
    boolean detectSharedXmlParser() default true;

    /**
     * Enable boxed-primitive-lock detection.
     * Detects {@code synchronized} blocks that lock on cached boxed primitives
     * ({@link Integer}/{@link Long} in range {@code -128..127}, {@link Boolean#TRUE}/
     * {@link Boolean#FALSE}, interned {@link String} literals), which are JVM-global
     * shared instances that cause unexpected contention.
     * @since 0.10.0
     */
    boolean detectBoxedPrimitiveLock() default true;

    /**
     * Enable shared-TimeZone mutation detection.
     * Detects {@link java.util.TimeZone} instances whose mutable state ({@code setRawOffset},
     * {@code setID}) is modified from multiple threads, causing silently wrong date/time
     * arithmetic.
     * @since 0.10.0
     */
    boolean detectSharedTimeZone() default true;

    /**
     * Enable uncaught-exception-handler detection.
     * Detects threads started without a custom {@link Thread.UncaughtExceptionHandler} that
     * subsequently throw, causing the exception to be silently discarded from the submitter's
     * perspective.
     * @since 0.10.0
     */
    boolean detectUncaughtExceptionHandler() default true;

    // ============= License Gating (Integration) =============

    /** Keygen Account ID. Defaults to System property 'keygen.account.id' if empty. */
    String keygenAccountId() default "";

    /** Keygen API Key. Defaults to System property 'keygen.api.key' if empty. */
    String keygenApiKey() default "";

    /** Keygen Product Id. Defaults to System property 'keygen.product.id' if empty. */
    String keygenProductId() default "";

    /** LemonSqueezy store subdomain (e.g. 'acme' for acme.lemonsqueezy.com). */
    String lemonSqueezyStore() default "";

    /** License key for Keygen validation. Defaults to System property 'license.key' if empty. */
    String licenseKey() default "";

    /** When true, use mock mode for LicenseGate (no network calls). Defaults to System property 'license.mock.mode' if empty. */
    boolean licenseMockMode() default false;
}

