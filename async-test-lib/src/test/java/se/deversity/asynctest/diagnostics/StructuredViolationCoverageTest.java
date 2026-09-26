package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.random.RandomGeneratorFactory;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.crypto.Cipher;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report with issues carries at least one structured {@link se.deversity.asynctest.report.Violation}.
 *
 * <p><strong>Why this exists.</strong> The {@code failOn} gate reads a finding's severity from the
 * report's public {@code structuredViolations} list first ({@link DetectorDefaultSeverity#structuredIn})
 * and falls back to guessing it from the text only when that list is empty. The preference is only
 * as good as the list: a detector whose report fills it on one path and not on another is judged by
 * its own chosen severity on the first and by a text match on the second, and a report type with no
 * list at all is always judged by text. Nothing checked either. {@code DetectorSeverityMarkerTest}
 * asks whether a detector <em>can</em> state a severity; this asks whether its reports actually do
 * when they fire (#774).
 *
 * <p>Two halves:
 * <ul>
 *   <li><strong>Coverage, over every detector.</strong> A report type either keeps the list or is
 *       named in {@link #TEXT_ONLY}. The list is a ratchet: it fails on a detector that is missing
 *       and not listed, and on a listed detector that has since gained the list, so it can only
 *       shrink.</li>
 *   <li><strong>Paths, driven.</strong> Every detector outside {@link #TEXT_ONLY} has at least one
 *       entry in {@link #PATHS}, and every entry drives that detector until its report has issues
 *       and then requires the structured list to be non-empty. A detector with more than one place
 *       where it writes a finding has one entry per place. A driver that fails to make its report
 *       fire fails the test rather than passing vacuously.</li>
 * </ul>
 *
 * <p>The three detectors that gained structured findings in #774 are also held to the severity
 * their text alone resolved to ({@link #TEXT_AGREES}), so the change could not move what an
 * existing {@code failOn} gate fails on.
 */
@DisplayName("Structured violations: a report with issues carries at least one")
class StructuredViolationCoverageTest {

    /**
     * Detectors whose reports still carry their findings as text only.
     *
     * <p>A debt register, pinned in #774 at the measured gap: every built-in detector with no
     * {@code structuredViolations} field on its report. For these the {@code failOn} gate reads
     * severity from a marker in the text or from {@code DetectorDefaultSeverity}, which is correct
     * today but is a guess about the whole report rather than a statement about each finding. To
     * clear an entry, add the list to the report, fill it wherever the text gains a line, at the
     * severity the text already resolves to, add a driver to {@link #PATHS}, and delete the entry
     * here (and the detector's {@code DetectorDefaultSeverity} entry, which
     * {@code DetectorSeverityMarkerTest} will then call redundant).
     */
    private static final Set<String> TEXT_ONLY = Set.of(
            "ABAProblemDetector", "AtomicNonAtomicUpdateDetector", "BlockingQueueDetector",
            "BoxedPrimitiveLockDetector", "BusyWaitDetector", "CacheConcurrencyDetector",
            "CalendarDetector", "CompletableFutureChainDetector",
            "CompletableFutureCommonPoolBlockingDetector",
            "CompletableFutureCompletionLeakDetector", "CompletableFutureExceptionDetector",
            "ConcurrentMapComputeRecursionDetector", "ConcurrentModificationDetector",
            "ConditionVariableDetector", "ConstructorSafetyValidator",
            "CopyOnWriteCollectionDetector", "CountDownLatchDetector", "CyclicBarrierDetector",
            "DeprecatedThreadApiDetector", "DoubleCheckedLockingDetector", "ExchangerDetector",
            "ExecutorDeadlockDetector", "ExecutorShutdownDetector", "ExplicitGcDetector",
            "FalseSharingDetector", "FinalFieldMutationDetector", "ForkJoinPoolDetector",
            "ForkJoinTaskBlockingDetector", "FutureBlockingDetector", "FutureIgnoredDetector",
            "GathererConcurrencyMisuseDetector", "HttpClientConcurrencyDetector",
            "InheritableThreadLocalMisuseDetector", "InterruptMonitor",
            "InterruptSwallowingDetector", "LatchMisuseDetector", "LazyConstantMisuseDetector",
            "LazyInitRaceDetector", "LivelockDetector", "LockContentionDetector",
            "LockDowngradeDetector", "LockLeakDetector", "LockOrderValidator",
            "MdcContextLeakDetector", "MemoryOrderingMonitor", "MissedSignalDetector",
            "MutableMapKeyDetector", "NestedMonitorLockoutDetector",
            "OptimisticReadValidationDetector", "ParallelStreamDetector", "PhaserDetector",
            "PipelineMonitor", "PublicLockExposureDetector", "ReadWriteLockMonitor",
            "ReentrantLockDetector", "ResourceLeakDetector", "ScheduledExecutorDetector",
            "ScopedValueMisuseDetector", "SemaphoreMisuseDetector", "SharedCollectionDetector",
            "SharedDecimalFormatDetector", "SharedFormatterDetector", "SharedMatcherDetector",
            "SharedRandomDetector", "SharedTimeZoneDetector", "SharedXmlParserDetector",
            "SimpleDateFormatDetector", "SleepInLockDetector", "StableValueMisuseDetector",
            "StampedLockDetector", "StatefulLambdaDetector", "StreamClosingDetector",
            "StringBuilderDetector", "StructuredConcurrencyMisuseDetector",
            "StructuredTaskScopeMisuseDetector", "SynchronizedCollectionIterationDetector",
            "SynchronizedNonFinalDetector", "SynchronizedOnLiteralDetector", "SynchronizerMonitor",
            "SystemPropertyMutationDetector", "ThreadFactoryDetector", "ThreadLeakDetector",
            "ThreadLocalContaminationDetector", "ThreadLocalMonitor", "ThreadPoolDeadlockDetector",
            "ThreadPoolMonitor", "ThreadStarvationDetector", "TimerDetector",
            "UnboundedQueueDetector", "UncaughtExceptionHandlerDetector",
            "VirtualThreadCarrierExhaustionDetector", "VirtualThreadContextLeakDetector",
            "VirtualThreadCpuBoundTaskDetector", "VirtualThreadPinningDetector",
            "VisibilityMonitor", "VolatileArrayDetector", "WaitTimeoutDetector", "WakeupDetector",
            "WeakReferenceRaceDetector");

    /**
     * Detectors whose structured severity must equal what their text alone resolves to.
     *
     * <p>These gained structured findings in #774. Their severity was already settled by the text
     * (a marker for the deadlock and race reports, the {@code HIGH} default for atomicity), so the
     * structured list restates it rather than changing it: a {@code failOn} gate that passed or
     * failed on one of them before still does.
     */
    private static final Set<String> TEXT_AGREES = Set.of(
            "DeadlockDetector", "RaceConditionDetector", "AtomicityValidator");

    /** One way to make a detector report: its name, what the path is, and how to drive it. */
    private record Path(String detector, String path, Drive drive) {
        @Override
        public String toString() {
            return detector + " [" + path + "]";
        }
    }

    /** Drives a detector and returns its report. */
    @FunctionalInterface
    private interface Drive {
        Object report() throws Exception;
    }

    private static final List<Path> PATHS = List.of(
            // ---- fixed in #774 ----
            new Path("DeadlockDetector", "platform threads deadlocked (JMX)",
                    () -> new DeadlockDetector.DeadlockReport(true)),
            new Path("DeadlockDetector", "virtual-thread cycle from the thread dump",
                    () -> new DeadlockDetector.DeadlockReport(false, List.of(
                            "vt-a -> vt-b, each waiting for a monitor the next one holds (java.lang.Object@1, java.lang.Object@2)"))),
            new Path("RaceConditionDetector", "two writers: hotspot and access sequence", () -> {
                RaceConditionDetector d = new RaceConditionDetector();
                Counter shared = new Counter();
                Runnable increment = () -> {
                    d.recordFieldRead(shared, "value");
                    shared.value++;
                    d.recordFieldWrite(shared, "value");
                };
                onTwoThreads(increment, increment);
                return d.analyze();
            }),
            new Path("RaceConditionDetector", "one writer, one reader: access sequence only", () -> {
                RaceConditionDetector d = new RaceConditionDetector();
                Counter shared = new Counter();
                onTwoThreads(() -> d.recordFieldWrite(shared, "value"),
                        () -> d.recordFieldRead(shared, "value"));
                return d.analyze();
            }),
            new Path("AtomicityValidator", "check-then-act", () -> {
                AtomicityValidator v = new AtomicityValidator();
                v.detectCheckThenActViolation("balance", 1, 2, true);
                return v.analyze();
            }),
            new Path("AtomicityValidator", "compound operation read then wrote a different value", () -> {
                AtomicityValidator v = new AtomicityValidator();
                v.recordCompoundOperationStart("transfer");
                v.recordFieldAccess("balance", 1, false);
                v.recordFieldAccess("balance", 2, true);
                v.recordCompoundOperationEnd("transfer");
                return v.analyze();
            }),
            new Path("AtomicityValidator", "mixed read/write across threads", () -> {
                AtomicityValidator v = new AtomicityValidator();
                Counter shared = new Counter();
                Runnable readModifyWrite = () -> {
                    v.recordFieldAccess("balance", shared.value, false);
                    shared.value++;
                    v.recordFieldAccess("balance", shared.value, true);
                };
                onTwoThreads(readModifyWrite, readModifyWrite);
                return v.analyze();
            }),
            new Path("AtomicityValidator", "writes only across threads: TOCTOU window only", () -> {
                AtomicityValidator v = new AtomicityValidator();
                onTwoThreads(() -> v.recordFieldAccess("balance", 1, true),
                        () -> v.recordFieldAccess("balance", 2, true));
                return v.analyze();
            }),

            // ---- already structured before #774 ----
            new Path("CompletableFutureBlockingCallbackDetector", "blocking call inside a callback", () -> {
                var d = new CompletableFutureBlockingCallbackDetector();
                d.recordEnterCallback("thenApply", Thread.currentThread());
                d.recordBlockingCall(Thread.currentThread(), "CompletableFuture.get");
                d.recordExitCallback(Thread.currentThread());
                return d.analyze();
            }),
            new Path("CompletableFutureCancellationPropagationDetector", "work ran on after the cancel", () -> {
                var d = new CompletableFutureCancellationPropagationDetector();
                d.recordWorkStarted("report", "fetch", Thread.currentThread());
                d.cancel(new CompletableFuture<String>(), "report", "view", false);
                d.recordWorkCompleted("report", "fetch", Thread.currentThread());
                return d.analyze();
            }),
            new Path("CompletableFutureCancellationPropagationDetector", "cancel(true) is ignored", () -> {
                var d = new CompletableFutureCancellationPropagationDetector();
                d.cancel(new CompletableFuture<String>(), "report", "view", true);
                return d.analyze();
            }),
            new Path("CompletableFutureCombinatorMisuseDetector", "combinator never awaited", () -> {
                var d = new CompletableFutureCombinatorMisuseDetector();
                var all = new CompletableFuture<Void>();
                d.recordCombinator(all, "writes", "allOf", 3, Thread.currentThread());
                d.recordConstituentCompleted(all, "a", false, Thread.currentThread());
                return d.analyze();
            }),
            new Path("CompletableFutureCombinatorMisuseDetector", "non-blocking read before completion", () -> {
                var d = new CompletableFutureCombinatorMisuseDetector();
                var all = new CompletableFuture<Void>();
                d.recordCombinator(all, "writes", "allOf", 2, Thread.currentThread());
                d.recordConstituentCompleted(all, "a", false, Thread.currentThread());
                d.recordAwait(all, "getNow", Thread.currentThread());
                d.recordConstituentCompleted(all, "b", false, Thread.currentThread());
                return d.analyze();
            }),
            new Path("CompletableFutureCombinatorMisuseDetector", "anyOf loser fails after the read", () -> {
                var d = new CompletableFutureCombinatorMisuseDetector();
                var any = new CompletableFuture<Object>();
                d.recordCombinator(any, "first-answer", "anyOf", 2, Thread.currentThread());
                d.recordConstituentCompleted(any, "fast", false, Thread.currentThread());
                d.recordAwait(any, "join", Thread.currentThread());
                d.recordConstituentCompleted(any, "slow", true, Thread.currentThread());
                return d.analyze();
            }),
            new Path("CompletableFutureCompletionRaceDetector", "losing completion attempt", () -> {
                var d = new CompletableFutureCompletionRaceDetector();
                var f = new CompletableFuture<String>();
                d.complete(f, "bridge", "first");
                d.complete(f, "bridge", "second");
                return d.analyze();
            }),
            new Path("CompletableFutureObtrudeDetector", "obtrude", () -> {
                var d = new CompletableFutureObtrudeDetector();
                d.recordObtrude(new CompletableFuture<String>(), "my-future", Thread.currentThread());
                return d.analyze();
            }),
            new Path("ConfinedArenaThreadEscapeDetector", "access after close", () -> {
                var d = new ConfinedArenaThreadEscapeDetector();
                Object arena = new Object();
                Object segment = new Object();
                Thread owner = new Thread(() -> { }, "arena-owner");
                d.recordArena(arena, "parseBuffer", owner);
                d.recordAllocation(segment, arena, "parseBuffer", 64);
                d.recordClose(arena, owner);
                d.recordAccess(segment, "parseBuffer", owner, false);
                return d.analyze();
            }),
            new Path("DaemonThreadHygieneDetector", "non-daemon thread still alive", () -> {
                var d = new DaemonThreadHygieneDetector();
                CountDownLatch release = new CountDownLatch(1);
                Thread t = new Thread(() -> awaitQuietly(release), "leak-thread");
                d.recordThread(t, "leak-thread");
                t.start();
                try {
                    return d.analyze();
                } finally {
                    release.countDown();
                    t.join();
                }
            }),
            new Path("FileChannelPositionRaceDetector", "implicit position shared across threads", () -> {
                var d = new FileChannelPositionRaceDetector();
                Object channel = new Object();
                onTwoThreads(() -> d.recordImplicitPositionAccess(channel, "read"),
                        () -> d.recordImplicitPositionAccess(channel, "write"));
                return d.analyze();
            }),
            new Path("FlowPublisherConcurrencyDetector", "onNext after onComplete", () -> {
                var d = new FlowPublisherConcurrencyDetector();
                Object subscriber = new Object();
                d.recordComplete(subscriber, Thread.currentThread());
                d.recordNextStart(subscriber, Thread.currentThread());
                d.recordNextEnd(subscriber);
                return d.analyze();
            }),
            new Path("HighContentionAtomicDetector", "failed CAS ratio above threshold", () -> {
                var d = new HighContentionAtomicDetector(20L);
                var counter = new AtomicLong();
                for (int i = 0; i < 15; i++) {
                    d.recordCasAttempt(counter, false);
                }
                inAnotherThread(() -> {
                    for (int i = 0; i < 10; i++) {
                        d.recordCasAttempt(counter, false);
                    }
                });
                return d.analyze();
            }),
            new Path("JdbcConnectionSharedDetector", "connection shared across threads", () -> {
                var d = new JdbcConnectionSharedDetector();
                Connection c = proxy(Connection.class);
                onTwoThreads(() -> d.recordAccess(c, "tx-conn", Thread.currentThread()),
                        () -> d.recordAccess(c, "tx-conn", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("LambdaLostUpdateDetector", "two threads read the same pre-value", () -> {
                var d = new LambdaLostUpdateDetector();
                Runnable task = () -> { };
                d.recordReadModifyWrite(task, "counter", 0, 1, Thread.currentThread());
                d.recordReadModifyWrite(task, "counter", 1, 2, Thread.currentThread());
                inAnotherThread(() -> d.recordReadModifyWrite(task, "counter", 1, 2, Thread.currentThread()));
                d.recordReadModifyWrite(task, "counter", 2, 3, Thread.currentThread());
                return d.analyze();
            }),
            new Path("LazyCollectionMisuseDetector", "element computed twice in one round", () -> {
                var d = new LazyCollectionMisuseDetector();
                d.markInvocationStart();
                Thread a = new Thread(() -> { }, "a");
                Thread b = new Thread(() -> { }, "b");
                d.recordComputeStart("BOARDS", 0, a);
                d.recordComputeStart("BOARDS", 0, b);
                d.recordComputeEnd("BOARDS", 0, a, "x");
                d.recordComputeEnd("BOARDS", 0, b, "x");
                return d.analyze();
            }),
            new Path("LockUpgradeDeadlockDetector", "write lock attempted under the read lock", () -> {
                var d = new LockUpgradeDeadlockDetector();
                var lock = new ReentrantReadWriteLock();
                d.recordReadLockAcquired(lock, "my-lock", Thread.currentThread());
                d.recordWriteLockAcquisitionAttempt(lock, "my-lock", Thread.currentThread());
                return d.analyze();
            }),
            new Path("NonAtomicConcurrentMapUpdateDetector", "check-then-act on one key across threads", () -> {
                var d = new NonAtomicConcurrentMapUpdateDetector();
                var map = new ConcurrentHashMap<String, String>();
                onTwoThreads(() -> d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread()),
                        () -> d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("NotifyWithoutMonitorDetector", "notify without the monitor", () -> {
                var d = new NotifyWithoutMonitorDetector();
                d.recordNotifyAttempt(new Object(), "queue");
                return d.analyze();
            }),
            new Path("PlatformThreadPerTaskDetector", "one-task platform thread churn", () -> {
                var d = new PlatformThreadPerTaskDetector();
                d.setChurnThreshold(4);
                for (int i = 0; i < 4; i++) {
                    Thread t = new Thread(() -> { }, "per-task-" + i);
                    d.recordThreadCreated(t);
                    t.start();
                    t.join();
                }
                return d.analyze();
            }),
            new Path("PlatformThreadPerTaskDetector", "thread-per-task executor on platform threads", () -> {
                var d = new PlatformThreadPerTaskDetector();
                try (ExecutorService perTask = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
                    d.registerExecutor(perTask, "platform-per-task");
                }
                return d.analyze();
            }),
            new Path("RecordMutableComponentLeakDetector", "component mutated while shared", () -> {
                var d = new RecordMutableComponentLeakDetector();
                List<String> items = new ArrayList<>(List.of("a"));
                Order order = new Order("o-1", items);
                d.recordShared(order, "order", new Thread(() -> { }, "record-a"));
                d.recordShared(order, "order", new Thread(() -> { }, "record-b"));
                items.add("b");
                return d.analyze();
            }),
            new Path("RecordMutableComponentLeakDetector", "mutable component, not mutated", () -> {
                var d = new RecordMutableComponentLeakDetector();
                Order order = new Order("o-2", new ArrayList<>(List.of("a")));
                d.recordShared(order, "order", new Thread(() -> { }, "record-a"));
                d.recordShared(order, "order", new Thread(() -> { }, "record-b"));
                return d.analyze();
            }),
            new Path("ScopeConfigurationMisuseDetector", "configured timeout discarded", () -> {
                var d = new ScopeConfigurationMisuseDetector();
                d.recordScopeOpened("scope-1", "orders", 3000L, null, Thread.currentThread());
                d.recordEffectiveConfiguration("scope-1", "orders", ScopeConfigurationMisuseDetector.NO_TIMEOUT);
                d.recordScopeClosed("scope-1");
                return d.analyze();
            }),
            new Path("ScopeJoinerMisuseDetector", "joiner passed to two scopes", () -> {
                var d = new ScopeJoinerMisuseDetector();
                Object joiner = new Object();
                d.recordJoinerBound(joiner, "orders", "scope-1", Thread.currentThread());
                d.recordJoinerBound(joiner, "orders", "scope-2", Thread.currentThread());
                return d.analyze();
            }),
            new Path("ScopeResultEscapeDetector", "result read after the scope closed", () -> {
                var d = new ScopeResultEscapeDetector();
                List<String> results = List.of("a");
                d.recordScopeOpened("scope-1", Thread.currentThread());
                d.recordJoinCompleted("scope-1");
                d.recordResultHandle(results, "orders", "scope-1");
                d.recordScopeClosed("scope-1");
                d.recordHandleRead(results, Thread.currentThread());
                return d.analyze();
            }),
            new Path("SharedByteBufferDetector", "positional state shared across threads", () -> {
                var d = new SharedByteBufferDetector();
                var buffer = ByteBuffer.allocate(16);
                onTwoThreads(() -> d.recordPositionalAccess(buffer, "put"),
                        () -> d.recordPositionalAccess(buffer, "flip"));
                return d.analyze();
            }),
            new Path("SharedCharsetCoderDetector", "encoder shared across threads", () -> {
                var d = new SharedCharsetCoderDetector();
                var encoder = StandardCharsets.UTF_8.newEncoder();
                onTwoThreads(() -> d.recordAccess(encoder, "encode", Thread.currentThread()),
                        () -> d.recordAccess(encoder, "encode", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("SharedChecksumDetector", "CRC32 shared across threads", () -> {
                var d = new SharedChecksumDetector();
                var crc = new CRC32();
                onTwoThreads(() -> d.recordAccess(crc, "update", Thread.currentThread()),
                        () -> d.recordAccess(crc, "getValue", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("SharedDeflaterDetector", "Deflater shared across threads", () -> {
                var d = new SharedDeflaterDetector();
                var deflater = new Deflater();
                try {
                    onTwoThreads(() -> d.recordAccess(deflater, "response-gzip", Thread.currentThread()),
                            () -> d.recordAccess(deflater, "response-gzip", Thread.currentThread()));
                    return d.analyze();
                } finally {
                    deflater.end();
                }
            }),
            new Path("SharedIteratorDetector", "iterator shared across threads", () -> {
                var d = new SharedIteratorDetector();
                Iterator<String> it = new ArrayList<>(List.of("a", "b", "c")).iterator();
                Runnable walk = () -> {
                    d.recordAccess(it, "hasNext");
                    d.recordAccess(it, "next");
                };
                onTwoThreads(walk, walk);
                return d.analyze();
            }),
            new Path("SharedJsonMapperReconfigDetector", "reconfigured by another thread after use", () -> {
                var d = new SharedJsonMapperReconfigDetector();
                Object mapper = new Object();
                d.recordUse(mapper);
                inAnotherThread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
                return d.analyze();
            }),
            new Path("SharedKdfDetector", "KDF shared across threads", () -> {
                var d = new SharedKdfDetector();
                Object kdf = new Object();
                Runnable derive = () -> d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", Thread.currentThread());
                onTwoThreads(derive, derive);
                return d.analyze();
            }),
            new Path("SharedMemorySegmentRaceDetector", "overlapping unguarded write and read", () -> {
                var d = new SharedMemorySegmentRaceDetector();
                Object segment = new Object();
                d.recordAccess(segment, "ringBuffer", 0, 8, true, new Thread(() -> { }, "seg-1"));
                d.recordAccess(segment, "ringBuffer", 4, 8, false, new Thread(() -> { }, "seg-2"));
                return d.analyze();
            }),
            new Path("SharedMemorySegmentRaceDetector", "conflicting guards", () -> {
                var d = new SharedMemorySegmentRaceDetector();
                Object segment = new Object();
                d.recordAccess(segment, "ringBuffer", 0, 8, true, new Thread(() -> { }, "seg-1"), "lockA");
                d.recordAccess(segment, "ringBuffer", 0, 8, true, new Thread(() -> { }, "seg-2"), "lockB");
                return d.analyze();
            }),
            new Path("SharedMessageDigestDetector", "digest shared across threads", () -> {
                var d = new SharedMessageDigestDetector();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                onTwoThreads(() -> d.recordAccess(md, "sha256", Thread.currentThread()),
                        () -> d.recordAccess(md, "sha256", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("SharedSecureRandomDetector", "SecureRandom shared across threads", () -> {
                var d = new SharedSecureRandomDetector();
                var rng = new SecureRandom();
                onTwoThreads(() -> d.recordAccess(rng, "shared-rng", Thread.currentThread()),
                        () -> d.recordAccess(rng, "shared-rng", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("SharedSplittableRandomDetector", "JEP 356 generator shared across threads", () -> {
                var d = new SharedSplittableRandomDetector();
                var shared = RandomGeneratorFactory.of("L64X128MixRandom").create(42);
                onTwoThreads(() -> d.recordAccess(shared, "lxm", "nextLong"),
                        () -> d.recordAccess(shared, "lxm", "nextLong"));
                return d.analyze();
            }),
            new Path("SharedStatefulCryptoDetector", "Cipher shared across threads", () -> {
                var d = new SharedStatefulCryptoDetector();
                var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                onTwoThreads(() -> d.recordAccess(cipher, "payload-cipher", Thread.currentThread()),
                        () -> d.recordAccess(cipher, "payload-cipher", Thread.currentThread()));
                return d.analyze();
            }),
            new Path("SpuriousWakeupDetector", "wait outside a loop", () -> {
                var d = new SpuriousWakeupDetector();
                d.recordWait(new Object(), "my-lock", false, Thread.currentThread());
                return d.analyze();
            }),
            new Path("StaticInitDeadlockDetector", "two-class initialization cycle", () -> {
                var d = new StaticInitDeadlockDetector();
                Thread a = new Thread(() -> { }, "clinit-a");
                Thread b = new Thread(() -> { }, "clinit-b");
                d.recordInitStart(InitFirst.class, a);
                d.recordInitStart(InitSecond.class, b);
                d.recordInitRequest(InitSecond.class, a);
                d.recordInitRequest(InitFirst.class, b);
                return d.analyze();
            }),
            new Path("ThisEscapeDetector", "this escaped the constructor", () -> {
                var d = new ThisEscapeDetector();
                d.recordConstructorEscape(new Object(), "bus.register(this)", Thread.currentThread());
                return d.analyze();
            }),
            new Path("ThreadLocalCacheDegradationDetector", "one cached value per virtual thread", () -> {
                var d = new ThreadLocalCacheDegradationDetector();
                ThreadLocal<StringBuilder> buffer = ThreadLocal.withInitial(StringBuilder::new);
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    threads.add(Thread.ofVirtual().start(() ->
                            d.recordCachedValue("BUFFER", buffer.get(), Thread.currentThread())));
                }
                for (Thread t : threads) {
                    t.join();
                }
                return d.analyze();
            }),
            new Path("ThreadLocalRandomMisuseDetector", "current() cached and used elsewhere", () -> {
                var d = new ThreadLocalRandomMisuseDetector();
                ThreadLocalRandom shared = ThreadLocalRandom.current();
                d.recordObtain(shared, "cached-rng", Thread.currentThread());
                inAnotherThread(() -> d.recordUse(shared, Thread.currentThread()));
                return d.analyze();
            }),
            new Path("TryLockMisuseDetector", "unlock after a failed tryLock", () -> {
                var d = new TryLockMisuseDetector();
                var lock = new ReentrantLock();
                d.recordTryLockResult(lock, "my-lock", false, Thread.currentThread());
                d.recordUnlock(lock, "my-lock", Thread.currentThread());
                return d.analyze();
            }),
            new Path("VarHandleNonAtomicUpdateDetector", "get then set beside an atomic update", () -> {
                var d = new VarHandleNonAtomicUpdateDetector();
                Holder h = new Holder();
                d.recordGet(Holder.COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, Thread.currentThread());
                d.recordSet(Holder.COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, Thread.currentThread());
                d.recordAtomicUpdate(Holder.COUNT, h, "count", new Thread(() -> { }, "vh-other"));
                return d.analyze();
            }),
            new Path("VirtualThreadMonitorSerializationDetector", "virtual threads queued on one monitor", () -> {
                var d = new VirtualThreadMonitorSerializationDetector();
                Object lock = new Object();
                CountDownLatch allQueued = new CountDownLatch(6);
                CountDownLatch release = new CountDownLatch(1);
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    threads.add(Thread.ofVirtual().start(() -> {
                        d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());
                        allQueued.countDown();
                        awaitQuietly(release);
                        d.recordMonitorAcquired(lock, Thread.currentThread());
                    }));
                }
                assertTrue(allQueued.await(5, TimeUnit.SECONDS), "virtual threads never queued");
                release.countDown();
                for (Thread t : threads) {
                    t.join();
                }
                return d.analyze();
            }),
            new Path("VirtualThreadPoolingDetector", "pool built on a virtual-thread factory", () -> {
                var d = new VirtualThreadPoolingDetector();
                var pool = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
                try {
                    d.registerExecutor(pool, "virtual-scheduled-pool");
                } finally {
                    pool.shutdownNow();
                }
                return d.analyze();
            }),
            new Path("VirtualThreadPoolingDetector", "one virtual thread reused across tasks", () -> {
                var d = new VirtualThreadPoolingDetector();
                Thread worker = Thread.ofVirtual().name("reused-vt").start(() -> {
                    d.recordTaskExecution("hand-rolled-pool");
                    d.recordTaskExecution("hand-rolled-pool");
                });
                worker.join();
                return d.analyze();
            }),
            new Path("VirtualThreadResourceSaturationDetector", "fan-out beyond a bounded resource", () -> {
                var d = new VirtualThreadResourceSaturationDetector();
                d.registerResource("connections", 2);
                CountDownLatch allWaiting = new CountDownLatch(8);
                CountDownLatch release = new CountDownLatch(1);
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    threads.add(Thread.ofVirtual().start(() -> {
                        d.recordAcquireStart("connections", Thread.currentThread());
                        allWaiting.countDown();
                        awaitQuietly(release);
                        d.recordAcquired("connections", Thread.currentThread());
                    }));
                }
                assertTrue(allWaiting.await(5, TimeUnit.SECONDS), "virtual threads never queued");
                release.countDown();
                for (Thread t : threads) {
                    t.join();
                }
                return d.analyze();
            }),
            new Path("WeakHashMapSharedDetector", "WeakHashMap shared across threads", () -> {
                var d = new WeakHashMapSharedDetector();
                var map = new WeakHashMap<String, String>();
                onTwoThreads(() -> d.recordAccess(map, "weak-cache", Thread.currentThread()),
                        () -> d.recordAccess(map, "weak-cache", Thread.currentThread()));
                return d.analyze();
            }));

    @Test
    @DisplayName("every detector's report keeps structured findings, or is pinned as text-only")
    void everyReportTypeKeepsStructuredFindingsOrIsPinned() {
        List<String> missing = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        Set<String> known = new TreeSet<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            known.add(row.detectorClass());
            boolean structured = reportTypes(row.detectorClass()).stream()
                    .anyMatch(StructuredViolationCoverageTest::hasStructuredField);
            if (!structured && !TEXT_ONLY.contains(row.detectorClass())) {
                missing.add(row.detectorClass());
            }
            if (structured && TEXT_ONLY.contains(row.detectorClass())) {
                stale.add(row.detectorClass());
            }
        }
        Set<String> unknown = new TreeSet<>(TEXT_ONLY);
        unknown.removeAll(known);

        assertTrue(missing.isEmpty(),
                "These detectors' reports keep no structuredViolations list, so the failOn gate can "
                        + "only guess their severity from the text. Keep the findings as Violations "
                        + "in a public structuredViolations field, or argue the detector into "
                        + "TEXT_ONLY in review: " + missing);
        assertTrue(stale.isEmpty(),
                "These detectors now keep structured findings. Remove them from TEXT_ONLY so the "
                        + "list keeps shrinking, and add a driver to PATHS: " + stale);
        assertTrue(unknown.isEmpty(),
                "TEXT_ONLY names classes DetectorTrust does not know: " + unknown);
    }

    @Test
    @DisplayName("every detector that keeps structured findings is driven here")
    void everyStructuredDetectorHasADrivenPath() {
        Set<String> driven = new TreeSet<>();
        PATHS.forEach(path -> driven.add(path.detector()));
        List<String> undriven = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (!TEXT_ONLY.contains(row.detectorClass()) && !driven.contains(row.detectorClass())) {
                undriven.add(row.detectorClass());
            }
        }
        assertTrue(undriven.isEmpty(),
                "A detector outside TEXT_ONLY with no driver is claimed to fill its structured "
                        + "list without anything checking it. Add one Path per place its report "
                        + "writes a finding: " + undriven);
    }

    @Test
    @DisplayName("a driven report with issues carries at least one structured violation")
    void aReportWithIssuesCarriesAStructuredViolation() {
        List<String> silentDrivers = new ArrayList<>();
        List<String> textOnly = new ArrayList<>();
        for (Path path : PATHS) {
            Object report = drive(path);
            if (!hasIssues(report)) {
                silentDrivers.add(path.toString());
            } else if (DetectorDefaultSeverity.structuredIn(report).isEmpty()) {
                textOnly.add(path.toString());
            }
        }
        assertTrue(silentDrivers.isEmpty(),
                "These drivers no longer make their detector report, so the check below says "
                        + "nothing about them. Fix the driver, not the assertion: " + silentDrivers);
        assertTrue(textOnly.isEmpty(),
                "These paths produce a report with issues and an empty structuredViolations list, "
                        + "so the failOn gate falls back to reading the text for exactly this "
                        + "finding. Add a Violation beside the text line, at the severity the text "
                        + "resolves to:\n  " + String.join("\n  ", textOnly));
    }

    @Test
    @DisplayName("the detectors structured in #774 keep the severity their text resolved to")
    void structuredSeverityAgreesWithTheText() {
        List<String> disagreeing = new ArrayList<>();
        for (Path path : PATHS) {
            if (!TEXT_AGREES.contains(path.detector())) {
                continue;
            }
            Object report = drive(path);
            IssueSeverity fromText = DetectorDefaultSeverity.of(path.detector(), report.toString());
            Optional<IssueSeverity> structured = DetectorDefaultSeverity.structuredIn(report);
            if (structured.isEmpty() || structured.get() != fromText) {
                disagreeing.add(path + ": text resolves to " + fromText + ", structured says "
                        + structured.map(Enum::name).orElse("nothing"));
            }
        }
        assertTrue(disagreeing.isEmpty(),
                "Structured findings were added to these detectors to state the severity the text "
                        + "already implied, not to change it. A difference here changes what a "
                        + "failOn gate fails on, which is an owner's decision and a changelog entry:\n  "
                        + String.join("\n  ", disagreeing));
    }

    // ---- reflection over report types, as DetectorSeverityMarkerTest reads them ----

    /** The types the detector's public no-argument methods return that answer {@code hasIssues()}. */
    private static List<Class<?>> reportTypes(String detectorClass) {
        Class<?> detector;
        try {
            detector = Class.forName("se.deversity.asynctest.diagnostics." + detectorClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DetectorTrust names " + detectorClass
                    + ", which is not a class in the diagnostics package", e);
        }
        List<Class<?>> types = new ArrayList<>();
        for (Method method : detector.getMethods()) {
            if (method.getParameterCount() != 0) continue;
            try {
                method.getReturnType().getMethod("hasIssues");
                types.add(method.getReturnType());
            } catch (NoSuchMethodException notAReport) {
                // any other accessor
            }
        }
        return types;
    }

    private static boolean hasStructuredField(Class<?> reportType) {
        try {
            return List.class.isAssignableFrom(
                    reportType.getField(DetectorDefaultSeverity.STRUCTURED_FIELD).getType());
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    private static Object drive(Path path) {
        try {
            return path.drive().report();
        } catch (Exception e) {
            throw new AssertionError("driver " + path + " failed", e);
        }
    }

    private static boolean hasIssues(Object report) {
        try {
            Method method = report.getClass().getMethod("hasIssues");
            method.trySetAccessible();
            return (boolean) method.invoke(report);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(report.getClass().getName() + " has no callable hasIssues()", e);
        }
    }

    // ---- driver helpers ----

    /** Runs the two actions on two live threads that meet on a barrier; a worker failure fails the driver. */
    private static void onTwoThreads(Runnable first, Runnable second) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> died = new AtomicReference<>();
        Thread t1 = new Thread(capturing(() -> { awaitQuietly(barrier); first.run(); }, died));
        Thread t2 = new Thread(capturing(() -> { awaitQuietly(barrier); second.run(); }, died));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        if (died.get() != null) {
            throw new AssertionError("a driver worker failed before finishing its recordings", died.get());
        }
    }

    /** Runs the action on one other live thread and waits for it. */
    private static void inAnotherThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> died = new AtomicReference<>();
        Thread t = new Thread(capturing(action, died));
        t.start();
        t.join();
        if (died.get() != null) {
            throw new AssertionError("a driver worker failed before finishing its recordings", died.get());
        }
    }

    private static Runnable capturing(Runnable work, AtomicReference<Throwable> died) {
        return () -> {
            try {
                work.run();
            } catch (Throwable failure) {
                died.compareAndSet(null, failure);
            }
        };
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface},
                (p, method, args) -> switch (method.getName()) {
                    case "equals" -> p == args[0];
                    case "hashCode" -> System.identityHashCode(p);
                    case "toString" -> iface.getSimpleName() + "Proxy";
                    default -> null;
                });
    }

    /** A plain mutable field for the race and atomicity drivers. */
    private static final class Counter {
        int value;
    }

    /** A record whose component is a mutable list. */
    private record Order(String id, List<String> items) { }

    /** Two distinct classes for the initialization-cycle driver; neither is ever initialized. */
    private static final class InitFirst { }

    private static final class InitSecond { }

    /** A VarHandle target. */
    static final class Holder {
        static final VarHandle COUNT;

        static {
            try {
                COUNT = MethodHandles.lookup().findVarHandle(Holder.class, "count", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("unused")
        volatile int count;
    }
}
