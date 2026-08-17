package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILocked;

/**
 * Enumerates all available detectors for type-safe opt-outs.
 * Used with {@link AsyncTest#excludes()}.
 */
@AILocked(reason = "Adding or removing a constant requires synchronized changes in five places: (1) @AsyncTest attribute, (2) AsyncTestConfig field, (3) AsyncTestConfig.Builder default, (4) the resolution line in AsyncTestConfig.build() ((detectAll || flag) && !excludes.contains(TYPE)), and (5) DetectorRegistry constructor. Adding a value here in isolation compiles and detects nothing. The lock is on the constant set, not the file: editing javadoc on existing constants cannot break that invariant and needs no ceremony.")
@AIKeepInSync(
    mirrors = {
        "se.deversity.asynctest.AsyncTest",
        "se.deversity.asynctest.AsyncTestConfig",
        "se.deversity.asynctest.DetectorRegistry",
        "se.deversity.asynctest.spi.LegacyDetectorFactories",
        "META-INF/async-test/builtin-detector-factories"
    },
    reason = "A detector is only reachable from the public API when all of these agree. The enum "
           + "constant is the name users type in @AsyncTest(excludes=...); the annotation attribute, "
           + "the config field and its Builder default carry it through resolution; the registry "
           + "constructor instantiates it; and the SPI factory plus its entry in the built-in factory list are "
           + "what detectAll loads. Adding the constant alone compiles and silently detects nothing.",
    enforcedBy = "se.deversity.asynctest.spi.AllDetectorsSpiCoverageTest"
)
@API(status = Status.STABLE)
public enum DetectorType {
    // Phase 1
    /** Enhanced deadlock detector that analyzes thread dumps and identifies circular lock dependencies, thread states, and provides actionable diagnostics. */
    DEADLOCKS,
    /** Monitors field access patterns to detect visibility issues (stale memory). */
    VISIBILITY,
    /** Detects thread starvation and livelocks. */
    LIVELOCKS,

    // Phase 2: Core
    /** Detects False Sharing - when multiple threads access adjacent memory locations that fall within the same CPU cache line (typically 64 bytes). */
    FALSE_SHARING,
    /** Detects spurious wakeups and lost notifications in wait/notify patterns. */
    WAKEUP_ISSUES,
    /** Validates that objects are fully constructed before being shared across threads. */
    CONSTRUCTOR_SAFETY,
    /** Detects the ABA Problem in atomic operations. */
    ABA_PROBLEM,
    /** Detects lock ordering violations that can cause deadlocks. */
    LOCK_ORDER,
    /** Monitors synchronizer behavior (CyclicBarrier, Phaser, CountDownLatch, etc.) Problems detected: - Threads not advancing synchronously through barriers - Phaser . */
    SYNCHRONIZERS,
    /** Monitors thread pool / executor health and issues. */
    THREAD_POOL,
    /** Detects memory ordering violations and compiler reordering issues. */
    MEMORY_ORDERING,
    /** Monitors event flow in async pipelines and detects signal loss. */
    ASYNC_PIPELINE,
    /** Monitors ReadWriteLock fairness and detects writer starvation. */
    READ_WRITE_LOCK_FAIRNESS,

    // Phase 2: Monitors
    /** Detects semaphore misuse patterns in concurrent code. */
    SEMAPHORE,
    /** Detects exception handling issues in CompletableFuture chains. */
    COMPLETABLE_FUTURE_EXCEPTIONS,
    /** Detects CompletableFuture instances that are created but never completed. */
    COMPLETABLE_FUTURE_COMPLETION_LEAKS,
    /** Detects virtual thread pinning issues. */
    VIRTUAL_THREAD_PINNING,
    /** Detects thread pool deadlock scenarios. */
    THREAD_POOL_DEADLOCK,
    /** Detects concurrent modification issues in collections during iteration. */
    CONCURRENT_MODIFICATIONS,
    /** Detects lock leak patterns where locks are acquired but never released. */
    LOCK_LEAKS,
    /** Detects concurrent use of non-thread-safe Random instances. */
    SHARED_RANDOM,
    /** Detects BlockingQueue misuse patterns in concurrent code. */
    BLOCKING_QUEUE,
    /** Detects Condition variable misuse patterns in concurrent code. */
    CONDITION_VARIABLES,
    /** Detects concurrent use of non-thread-safe SimpleDateFormat instances. */
    SIMPLE_DATE_FORMAT,
    /** Detects unsafe operations in parallel streams. */
    PARALLEL_STREAMS,
    /** Detects resource leak patterns in concurrent code. */
    RESOURCE_LEAKS,

    // Phase 2: Additional Concurrency
    /** Detects CountDownLatch misuse patterns: - Latch timeout (await with timeout expiring) - Missing countDown (latch never reaches zero) - Extra countDown (more cou. */
    COUNTDOWN_LATCH,
    /** Detects CyclicBarrier misuse patterns: - Barrier timeout (await with timeout expiring) - Broken barrier (barrier broken due to thread interruption or timeout) -. */
    CYCLIC_BARRIER,
    /** Detects ReentrantLock misuse patterns: - Lock starvation (thread waiting excessively long) - Unfair lock acquisition (threads not acquiring in FIFO order) - Loc. */
    REENTRANT_LOCK,
    /** Detects volatile array element visibility issues. */
    VOLATILE_ARRAY,
    /** Detects broken double-checked locking patterns. */
    DOUBLE_CHECKED_LOCKING,
    /** Detects wait/notify patterns without timeout (potential deadlock). */
    WAIT_TIMEOUT,
    /** Detects high lock contention — monitors where many threads compete to acquire the same lock, causing threads to spend significant time in BLOCKED state. */
    LOCK_CONTENTION,
    /** Detects the anti-pattern of synchronizing on a non-final, reassignable object reference. */
    SYNCHRONIZED_NON_FINAL,
    /** Detects missed (lost) signals — situations where notify() or notifyAll() is called on a condition before any thread is waiting on it, causing the signal to be s. */
    MISSED_SIGNAL,
    /** Detects lazy-initialization races — situations where multiple threads simultaneously observe a field as null and each proceeds to initialize it, causing the ini. */
    LAZY_INIT_RACE,

    // Phase 2: Advanced Concurrency Utilities
    /** Detects Phaser misuse patterns: - Missing arrive() calls (phaser never advances) - Phaser timeout (awaitAdvance with timeout expiring) - Phaser termination (pha. */
    PHASER,
    /** Detects StampedLock misuse patterns: - Optimistic read without validation - Lock upgrade issues (optimistic → write) - Stamp not released in finally block - Wro. */
    STAMPED_LOCK,
    /** Detects Exchanger misuse patterns: - Exchange timeout (exchange with timeout expiring) - Missing exchange partner (odd number of threads) - InterruptedException. */
    EXCHANGER,
    /** Detects ScheduledExecutorService misuse patterns: - Task scheduling without proper shutdown - Fixed delay vs fixed rate confusion - Long-running tasks blocking . */
    SCHEDULED_EXECUTOR,
    /** Detects ForkJoinPool misuse patterns: - Fork without join - RecursiveTask not returning result - Pool starvation (too few threads) - Exception in forked tasks. */
    FORK_JOIN_POOL,
    /** Detects ThreadFactory misuse patterns: - Missing uncaught exception handler - Non-daemon threads in thread pools - Missing thread naming convention - Thread pri. */
    THREAD_FACTORY,

    // Phase 3
    /** Detects potential race conditions by tracking cross-thread field accesses. */
    RACE_CONDITIONS,
    /** Monitors ThreadLocal lifecycle usage to detect leaks and poor cleanup. */
    THREAD_LOCAL_LEAKS,
    /** Detects spin loops that perform excessive work before yielding or blocking. */
    BUSY_WAITING,
    /** Tracks compound operations that should behave atomically. */
    ATOMICITY_VIOLATIONS,
    /** Tracks caught interrupts and whether they were restored or ignored. */
    INTERRUPT_MISHANDLING,

    // Phase 4: Infrastructure & Resource Management
    /** Detects thread leaks in concurrent code. */
    THREAD_LEAKS,
    /** Detects Thread.sleep() calls while holding a lock. */
    SLEEP_IN_LOCK,
    /** Detects unbounded queue usage in concurrent code. */
    UNBOUNDED_QUEUE,
    /** Detects thread starvation in thread pools. */
    THREAD_STARVATION,

    // Phase 5: Thread-Safety of Common Types
    /** Detects concurrent use of non-thread-safe java.util.Calendar instances. */
    CALENDAR,
    /** Detects non-thread-safe collections shared across multiple threads without synchronization. */
    SHARED_COLLECTIONS,
    /** Detects misuse of java.util.Timer in concurrent code. */
    TIMER,
    /** Detects CopyOnWriteArrayList and CopyOnWriteArraySet used in write-heavy concurrent scenarios where the copy-on-write overhead becomes a significant performance. */
    COPY_ON_WRITE_COLLECTIONS,
    /** Detects StringBuilder instances shared across multiple threads without synchronization. */
    STRING_BUILDER,

    // Phase 6: Virtual Thread Concurrency (Java 21+)
    /** Detects misuse of Java 21+ Structured Concurrency (StructuredTaskScope). */
    STRUCTURED_CONCURRENCY,
    /** Detects ThreadLocal context leaks in virtual threads. */
    VIRTUAL_THREAD_CONTEXT_LEAKS,
    /** Detects misuse of Java 21+ ScopedValue. */
    SCOPED_VALUE,
    /** Detects CPU-bound tasks running on virtual threads. */
    VIRTUAL_THREAD_CPU_BOUND,
    /** Detects potential carrier thread exhaustion caused by concurrent blocking of virtual threads. */
    VIRTUAL_THREAD_CARRIER_EXHAUSTION,

    // Phase 7: High-Level Concurrency Patterns
    /** Detects HTTP client concurrency issues, particularly with Java 11+ HttpClient. */
    HTTP_CLIENT,
    /** Detects I/O stream (InputStream, OutputStream, Reader, Writer) not being properly closed in concurrent code. */
    STREAM_CLOSING,
    /** Detects concurrent access to non-thread-safe cache implementations. */
    CACHE_CONCURRENCY,
    /** Detects improper CompletableFuture chain usage in concurrent code. */
    COMPLETABLEFUTURE_CHAIN,

    // Phase 8: Lifecycle & Structural Correctness
    /** Detects ExecutorService instances that are created and used but never properly shut down, or shut down without a subsequent awaitTermination() call. */
    EXECUTOR_SHUTDOWN,
    /** Detects mutable objects used as java.util.HashMap / java.util.HashSet keys that are mutated after insertion. */
    MUTABLE_MAP_KEY,
    /** Detects the nested monitor lockout anti-pattern: performing a blocking operation (e.g. */
    NESTED_MONITOR_LOCKOUT,
    /** Detects incorrect java.util.concurrent.locks.ReentrantReadWriteLock downgrade and upgrade patterns. */
    LOCK_DOWNGRADE,
    /** Detects misuse of InheritableThreadLocal in thread-pool environments. */
    INHERITABLE_THREAD_LOCAL,

    // Phase 9 (Repository & Environment State) held UNCOMMITTED_CHANGES until it was
    // removed after 1.7.2: a git-status environment check, not a concurrency property.

    // Phase 10: API Traps & Subtle Concurrency Bugs
    /** Detects ThreadLocal values that bleed from one task into the next task executing on the same pooled thread — cross-task state contamination. */
    THREAD_LOCAL_CONTAMINATION,
    /** Detects non-atomic compound updates on AtomicInteger, AtomicLong, AtomicReference, and similar: using get() then set() instead of compareAndSet(), silently losi. */
    ATOMIC_NON_ATOMIC_UPDATE,
    /** Detects iteration over Collections#synchronizedList, Collections#synchronizedMap, or Collections#synchronizedSet wrappers without holding the wrapper's intrinsi. */
    SYNCHRONIZED_COLLECTION_ITERATION,
    /** Detects java.util.Formatter, java.io.PrintWriter, and java.io.PrintStream instances shared across multiple threads without external synchronization. */
    SHARED_FORMATTER,
    /** Detects recursive calls to ConcurrentHashMap#computeIfAbsent (or compute / computeIfPresent / merge) on the same map and key from the same thread — a well-known. */
    CONCURRENT_MAP_COMPUTE_RECURSION,
    /** Detects synchronized blocks that lock on interned String literals or JVM-cached boxed primitives (Integer / Long in the range [-128, 127]). */
    SYNCHRONIZED_ON_LITERAL,
    /** Detects classes that use synchronized(this) (or synchronized instance methods) while this is publicly accessible — exposing the internal lock to external caller. */
    PUBLIC_LOCK_EXPOSURE,
    /** Detects blocking calls (Thread#sleep, Object#wait, Future.get(), blocking I/O) made from within a java.util.concurrent.ForkJoinTask body. */
    FORK_JOIN_TASK_BLOCKING,
    /** Detects incorrect usage of java.util.concurrent.locks.StampedLock optimistic reads: reading data after tryOptimisticRead() without calling validate(stamp), or c. */
    OPTIMISTIC_READ_VALIDATION,
    /** Detects blocking operations (Thread#sleep, Object#wait, blocking I/O, Future.get()) running inside CompletableFuture stages that were submitted to the common Fo. */
    CF_COMMON_POOL_BLOCKING,

    // Phase 11: Thread-Safety of Additional Types & Patterns
    /** Detects java.util.regex.Matcher instances shared across multiple threads. */
    SHARED_MATCHER,
    /** Detects java.text.DecimalFormat and java.text.NumberFormat instances shared across multiple threads without external synchronization. */
    SHARED_DECIMAL_FORMAT,
    /** Detects race conditions around java.lang.ref.WeakReference and java.lang.ref.SoftReference get() calls. */
    WEAK_REFERENCE_RACE,
    /** Detects lambda / Runnable / java.util.concurrent.Callable instances whose captured mutable state is mutated concurrently from multiple threads. */
    STATEFUL_LAMBDA,
    /** Detects java.security.MessageDigest instances shared across multiple threads. */
    SHARED_MESSAGE_DIGEST,

    // Phase 12: Operational & Hygiene Concurrency Issues
    /** Detects InterruptedException catches where the interrupt flag is silently swallowed. */
    INTERRUPT_SWALLOWING,
    /** Detects SLF4J MDC (Mapped Diagnostic Context) entries that are not cleared at task end, causing leakage to the next task run on the same pooled thread. */
    MDC_CONTEXT_LEAK,
    /** Detects concurrent mutations to JVM system properties via System#setProperty or System#clearProperty during an async test run. */
    SYSTEM_PROPERTY_MUTATION,
    /** Detects java.util.concurrent.Future instances returned from java.util.concurrent.ExecutorService#submit (or similar) that are never inspected. */
    FUTURE_IGNORED,
    /** Detects explicit garbage-collection invocations (System#gc() or Runtime#gc()) during a concurrent test run. */
    EXPLICIT_GC,
    /** Detects use of deprecated and unsafe Thread API methods: Thread.stop(), Thread.suspend(), Thread.resume(), Thread.destroy(), and Thread.countStackFrames(). */
    DEPRECATED_THREAD_API,
    /** Detects XML parser instances shared across multiple threads. */
    SHARED_XML_PARSER,
    /** Detects synchronized blocks that lock on cached boxed primitives or on JEP 390 value-based classes. */
    BOXED_PRIMITIVE_LOCK,
    /** Detects java.util.TimeZone instances whose mutable state is modified while being accessed from multiple threads. */
    SHARED_TIMEZONE,
    /** Detects threads that are started without a custom Thread.UncaughtExceptionHandler and that subsequently throw an uncaught exception. */
    UNCAUGHT_EXCEPTION_HANDLER,

    // Phase 13: Additional concurrency-bug categories (1.0.0+)
    /** Detects Thread instances created by user code without Thread#setDaemon(boolean) setDaemon(true) that remain alive at detector tear-down. */
    DAEMON_THREAD_HYGIENE,
    /** Detects attempted Object#notify() / Object#notifyAll() calls where the calling thread does not hold the target monitor. */
    NOTIFY_WITHOUT_MONITOR,
    /** Detects SecureRandom instances accessed from multiple threads. */
    SHARED_SECURE_RANDOM,
    /** Detects WeakHashMap or IdentityHashMap instances accessed from more than one thread. */
    WEAK_HASH_MAP_SHARED,
    /** Detects Connection, Statement, PreparedStatement, or ResultSet instances accessed from more than one thread. */
    JDBC_CONNECTION_SHARED,

    // Phase 14: Additional thread-unsafe primitives & publication hazards (1.7.0+)
    /** Detects stateful JCA cryptographic primitives — Cipher, Mac, and Signature — shared across multiple threads. */
    SHARED_STATEFUL_CRYPTO,
    /** Detects non-atomic check-then-act compound operations on a ConcurrentMap. */
    CONCURRENT_MAP_CHECK_THEN_ACT,
    /** Detects Deflater / Inflater instances shared across threads. */
    SHARED_DEFLATER,
    /** Detects this-escape: a constructor publishing a reference to the object being built before construction finishes. */
    THIS_ESCAPE,
    /** Detects misuse of ThreadLocalRandom: caching the reference returned by ThreadLocalRandom#current() and using it from a different thread. */
    THREAD_LOCAL_RANDOM_MISUSE,

    // Phase 15: Asynchronous flow & lock-usage hazards (1.8.0+)
    /** Detects CompletableFuture.obtrudeValue() or obtrudeException() calls which bypass normal completion pipelines and trigger race conditions or state inconsistency. */
    COMPLETABLE_FUTURE_OBTRUDE_ABUSE,
    /** Detects wait() or Condition.await() calls invoked outside of a while loop condition check, exposing the thread to spurious wakeups. */
    SPURIOUS_WAKEUP_HAZARD,
    /** Detects attempts to upgrade a ReentrantReadWriteLock from a read lock to a write lock on the same thread, which inevitably deadlocks. */
    LOCK_UPGRADE_DEADLOCK,
    /** Detects misuse of Lock.tryLock(), such as calling unlock() unconditionally when tryLock() returned false. */
    TRY_LOCK_MISUSE,
    /** Detects blocking calls (like get(), join(), sleep()) inside CompletableFuture callback pipelines, which can cause pool thread starvation or deadlocks. */
    COMPLETABLE_FUTURE_BLOCKING_CALLBACK,

    // Phase 16: JDK 25/26 preview-era concurrency detectors
    /** Detects misuse of Java 25+ StableValue (JEP 502 — Stable Values, Preview in JDK 25, continuing in JDK 26). */
    STABLE_VALUE_MISUSE,
    /** Detects misuse of the StructuredTaskScope API (JEP 505/525 — Structured Concurrency; fifth preview in JDK 25, sixth preview in JDK 26). */
    STRUCTURED_TASK_SCOPE_MISUSE,
    /** Detects unsafe use of Stream.gather(Gatherer) (JEP 485 — Stream Gatherers, finalized in JDK 24 and the standard intermediate-operation extension point in JDK 25. */
    GATHERER_CONCURRENCY_MISUSE,

    // Phase 17: Shared stateful JDK objects, I/O position races & contention advisories
    /** Detects java.nio.Buffer / java.nio.ByteBuffer instances shared across threads without coordination. */
    SHARED_BYTE_BUFFER,
    /** Detects CharsetEncoder / CharsetDecoder instances shared across threads. */
    SHARED_CHARSET_CODER,
    /** Detects Checksum implementations (e.g. */
    SHARED_CHECKSUM,
    /** Detects FileChannel / SeekableByteChannel instances whose implicit position is read or mutated from more than one thread. */
    FILE_CHANNEL_POSITION_RACE,
    /** Detects a single Iterator, ListIterator, or Spliterator instance being driven from more than one thread. */
    SHARED_ITERATOR,
    /** Advisory detector for hot compare-and-swap loops on shared AtomicLong/AtomicInteger/AtomicReference instances that would perform better as LongAdder/LongAccumul. */
    HIGH_CONTENTION_ATOMIC,
    /** Detects serializer/mapper instances (Jackson ObjectMapper, a Gson built via GsonBuilder, or similar) that are reconfigured after concurrent use has begun. */
    SHARED_JSON_MAPPER_RECONFIG,

    // Phase 18: JDK 25/26 GA-era concurrency detectors (1.8.0+)
    /** Detects misuse of Java 26+ LazyConstant (Lazy Constants, second preview in JDK 26 — the renamed and radically simplified successor of the JDK 25 StableValue pre. */
    LAZY_CONSTANT_MISUSE,
    /** Detects reflective mutation of final fields (Field.setAccessible(true) + Field.set(...)), which JDK 26 warns about and future JDK releases will deny by default . */
    FINAL_FIELD_MUTATION,
    /** Detects javax.crypto.KDF (Key Derivation Function, JEP 510 — final in JDK 25) instances shared across threads. */
    SHARED_KDF,

    // Executor / future / latch detectors that shipped implemented and tested but unwired
    /** Detects CountDownLatch-style misuse such as missing or extra countdowns. */
    LATCH_MISUSE,
    /** Detects self-deadlock patterns in single-thread or bounded executors. */
    EXECUTOR_DEADLOCK,
    /** Detects blocking waits on sibling futures inside bounded executors. */
    FUTURE_BLOCKING,

    // Phase 19: reactive-streams (java.util.concurrent.Flow) detectors
    /** Detects reactive-streams contract violations on Flow subscribers: overlapping onNext delivery, signals after a terminal signal, and deliveries exceeding recorded demand. */
    FLOW_PUBLISHER_CONCURRENCY,

    // Phase 20: FFM, VarHandle, record and class-initialization hazards (1.8.0+)
    /** Detects memory segments from a confined Arena (FFM, JDK 22+) escaping to a non-owner thread, and access to segments whose arena has been closed. */
    CONFINED_ARENA_THREAD_ESCAPE,
    /** Detects unsynchronized concurrent access to overlapping byte ranges of a shared MemorySegment, and use of a segment after its arena closed. */
    SHARED_MEMORY_SEGMENT_RACE,
    /** Detects non-atomic get-then-set read-modify-write sequences through a VarHandle, and plain-mode access to a location several threads share. */
    VAR_HANDLE_NON_ATOMIC_UPDATE,
    /** Detects records shared across threads whose components hold mutable state, and record components observed to change contents while shared. */
    RECORD_MUTABLE_COMPONENT_LEAK,
    /** Detects deadlocks between class initializers, which ThreadMXBean.findDeadlockedThreads() cannot see because a class init lock is not a monitor. */
    STATIC_INIT_DEADLOCK,

    // Phase 21: Virtual-thread-era executor hazards & shared generators (1.8.0+)
    /** Detects virtual threads being pooled or reused across tasks — JEP 444's central anti-pattern: a virtual thread is per-task and must never be pooled. */
    VIRTUAL_THREAD_POOLING,
    /** Detects thread-per-task execution on platform threads — unbounded OS-thread creation where virtual threads (or a bounded pool) belong. */
    PLATFORM_THREAD_PER_TASK,
    /** Detects SplittableRandom and JEP 356 RandomGenerator instances shared across threads — not thread-safe; concurrent use silently corrupts the sequence. */
    SHARED_SPLITTABLE_RANDOM,

    // Phase 22: CompletableFuture publication and lambda capture hazards (1.10.0+)
    /** Detects threads racing to complete the same CompletableFuture, where the loser's value or exception is discarded unread. */
    COMPLETABLE_FUTURE_COMPLETION_RACE,
    /** Detects stage work that keeps running after the future in front of it was cancelled, and cancel(true) calls on a type that never interrupts. */
    COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION,
    /** Detects allOf/anyOf results dropped or read without waiting, and anyOf failures that reach no handler. */
    COMPLETABLE_FUTURE_COMBINATOR_MISUSE,
    /** Detects proven lost updates to a lambda's captured state: two threads read the same value before writing back. */
    LAMBDA_LOST_UPDATE,

    // Phase 23: virtual-thread scale hazards (1.11.0+)
    /** Detects an unbounded virtual-thread fan-out queueing on a bounded resource - JEP 444's "limit the resource, not the threads". */
    VIRTUAL_THREAD_RESOURCE_SATURATION,
    /** Detects a monitor serialising a large virtual-thread fan-out - the throughput limit JEP 491 left behind when it removed pinning. */
    VIRTUAL_THREAD_MONITOR_SERIALIZATION,
    /** Detects a ThreadLocal that was a bounded cache under a pool and became a per-task allocator under virtual threads. */
    THREAD_LOCAL_CACHE_DEGRADATION
}
