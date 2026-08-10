package se.deversity.asynctest.spi.adapters;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.ABAProblemDetector;
import se.deversity.asynctest.diagnostics.AtomicNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.BoxedPrimitiveLockDetector;
import se.deversity.asynctest.diagnostics.BusyWaitDetector;
import se.deversity.asynctest.diagnostics.CacheConcurrencyDetector;
import se.deversity.asynctest.diagnostics.CalendarDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureChainDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCommonPoolBlockingDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureCompletionLeakDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureExceptionDetector;
import se.deversity.asynctest.diagnostics.ConcurrentMapComputeRecursionDetector;
import se.deversity.asynctest.diagnostics.ConcurrentModificationDetector;
import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;
import se.deversity.asynctest.diagnostics.CopyOnWriteCollectionDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.CyclicBarrierDetector;
import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.DeprecatedThreadApiDetector;
import se.deversity.asynctest.diagnostics.DoubleCheckedLockingDetector;
import se.deversity.asynctest.diagnostics.ExchangerDetector;
import se.deversity.asynctest.diagnostics.ExecutorShutdownDetector;
import se.deversity.asynctest.diagnostics.ExplicitGcDetector;
import se.deversity.asynctest.diagnostics.FalseSharingDetector;
import se.deversity.asynctest.diagnostics.ForkJoinPoolDetector;
import se.deversity.asynctest.diagnostics.ForkJoinTaskBlockingDetector;
import se.deversity.asynctest.diagnostics.FutureIgnoredDetector;
import se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector;
import se.deversity.asynctest.diagnostics.HttpClientConcurrencyDetector;
import se.deversity.asynctest.diagnostics.InheritableThreadLocalMisuseDetector;
import se.deversity.asynctest.diagnostics.InterruptMonitor;
import se.deversity.asynctest.diagnostics.InterruptSwallowingDetector;
import se.deversity.asynctest.diagnostics.JdbcConnectionSharedDetector;
import se.deversity.asynctest.diagnostics.LazyInitRaceDetector;
import se.deversity.asynctest.diagnostics.LivelockDetector;
import se.deversity.asynctest.diagnostics.LockContentionDetector;
import se.deversity.asynctest.diagnostics.LockDowngradeDetector;
import se.deversity.asynctest.diagnostics.LockLeakDetector;
import se.deversity.asynctest.diagnostics.LockOrderValidator;
import se.deversity.asynctest.diagnostics.MdcContextLeakDetector;
import se.deversity.asynctest.diagnostics.MemoryOrderingMonitor;
import se.deversity.asynctest.diagnostics.MissedSignalDetector;
import se.deversity.asynctest.diagnostics.MutableMapKeyDetector;
import se.deversity.asynctest.diagnostics.NestedMonitorLockoutDetector;
import se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector;
import se.deversity.asynctest.diagnostics.NotifyWithoutMonitorDetector;
import se.deversity.asynctest.diagnostics.OptimisticReadValidationDetector;
import se.deversity.asynctest.diagnostics.ParallelStreamDetector;
import se.deversity.asynctest.diagnostics.PhaserDetector;
import se.deversity.asynctest.diagnostics.PipelineMonitor;
import se.deversity.asynctest.diagnostics.PublicLockExposureDetector;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.diagnostics.ReadWriteLockMonitor;
import se.deversity.asynctest.diagnostics.ReentrantLockDetector;
import se.deversity.asynctest.diagnostics.ResourceLeakDetector;
import se.deversity.asynctest.diagnostics.ScheduledExecutorDetector;
import se.deversity.asynctest.diagnostics.ScopedValueMisuseDetector;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.diagnostics.SharedDecimalFormatDetector;
import se.deversity.asynctest.diagnostics.SharedDeflaterDetector;
import se.deversity.asynctest.diagnostics.SharedFormatterDetector;
import se.deversity.asynctest.diagnostics.SharedMatcherDetector;
import se.deversity.asynctest.diagnostics.SharedRandomDetector;
import se.deversity.asynctest.diagnostics.SharedSecureRandomDetector;
import se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector;
import se.deversity.asynctest.diagnostics.SharedTimeZoneDetector;
import se.deversity.asynctest.diagnostics.SharedXmlParserDetector;
import se.deversity.asynctest.diagnostics.SimpleDateFormatDetector;
import se.deversity.asynctest.diagnostics.SleepInLockDetector;
import se.deversity.asynctest.diagnostics.StableValueMisuseDetector;
import se.deversity.asynctest.diagnostics.StampedLockDetector;
import se.deversity.asynctest.diagnostics.StatefulLambdaDetector;
import se.deversity.asynctest.diagnostics.StreamClosingDetector;
import se.deversity.asynctest.diagnostics.StringBuilderDetector;
import se.deversity.asynctest.diagnostics.StructuredConcurrencyMisuseDetector;
import se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector;
import se.deversity.asynctest.diagnostics.SynchronizedCollectionIterationDetector;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;
import se.deversity.asynctest.diagnostics.SynchronizedOnLiteralDetector;
import se.deversity.asynctest.diagnostics.SynchronizerMonitor;
import se.deversity.asynctest.diagnostics.SystemPropertyMutationDetector;
import se.deversity.asynctest.diagnostics.ThisEscapeDetector;
import se.deversity.asynctest.diagnostics.ThreadFactoryDetector;
import se.deversity.asynctest.diagnostics.ThreadLeakDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalContaminationDetector;
import se.deversity.asynctest.diagnostics.ThreadLocalMonitor;
import se.deversity.asynctest.diagnostics.ThreadLocalRandomMisuseDetector;
import se.deversity.asynctest.diagnostics.ThreadPoolDeadlockDetector;
import se.deversity.asynctest.diagnostics.ThreadPoolMonitor;
import se.deversity.asynctest.diagnostics.ThreadStarvationDetector;
import se.deversity.asynctest.diagnostics.TimerDetector;
import se.deversity.asynctest.diagnostics.UnboundedQueueDetector;
import se.deversity.asynctest.diagnostics.UncaughtExceptionHandlerDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCarrierExhaustionDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadContextLeakDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadPinningDetector;
import se.deversity.asynctest.diagnostics.VisibilityMonitor;
import se.deversity.asynctest.diagnostics.VolatileArrayDetector;
import se.deversity.asynctest.diagnostics.WaitTimeoutDetector;
import se.deversity.asynctest.diagnostics.WakeupDetector;
import se.deversity.asynctest.diagnostics.WeakHashMapSharedDetector;
import se.deversity.asynctest.diagnostics.WeakReferenceRaceDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureObtrudeDetector;
import se.deversity.asynctest.diagnostics.SpuriousWakeupDetector;
import se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector;
import se.deversity.asynctest.diagnostics.TryLockMisuseDetector;
import se.deversity.asynctest.diagnostics.CompletableFutureBlockingCallbackDetector;
import se.deversity.asynctest.diagnostics.SharedByteBufferDetector;
import se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector;
import se.deversity.asynctest.diagnostics.SharedChecksumDetector;
import se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector;
import se.deversity.asynctest.diagnostics.SharedIteratorDetector;
import se.deversity.asynctest.diagnostics.HighContentionAtomicDetector;
import se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector;
import se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector;
import se.deversity.asynctest.diagnostics.FinalFieldMutationDetector;
import se.deversity.asynctest.diagnostics.SharedKdfDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.diagnostics.FlowPublisherConcurrencyDetector;
import se.deversity.asynctest.diagnostics.FutureBlockingDetector;
import se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector;
import se.deversity.asynctest.diagnostics.SharedMemorySegmentRaceDetector;
import se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector;
import se.deversity.asynctest.diagnostics.StaticInitDeadlockDetector;
import se.deversity.asynctest.diagnostics.VirtualThreadPoolingDetector;
import se.deversity.asynctest.diagnostics.PlatformThreadPerTaskDetector;
import se.deversity.asynctest.diagnostics.SharedSplittableRandomDetector;
import se.deversity.asynctest.spi.Detector;
import se.deversity.asynctest.spi.DetectorFactory;

/**
 * SPI factory bundle covering every legacy detector that does not yet have a
 * dedicated {@link DetectorFactory}. Each inner class is a tiny adapter:
 * declares its {@link DetectorType}, consults the matching boolean on
 * {@link AsyncTestConfig}, and wraps the detector instance in a
 * {@link LegacyDetectorAdapter}.
 *
 * <p>All inner classes are registered individually in
 * {@code META-INF/services/se.deversity.asynctest.spi.DetectorFactory} so
 * {@link java.util.ServiceLoader} discovers each one.
 *
 * <p>The dedicated
 * {@link SharedMessageDigestDetectorFactory} (which surfaces structured
 * violations directly) is intentionally NOT duplicated here — it remains the
 * canary for the "typed adapter" pattern.
 *
 * @since 1.6.0
 */
public final class LegacyDetectorFactories {

    private LegacyDetectorFactories() {}

    // ---------- Phase 1 — Core (always-on; SPI-exposed for parity) ----------

    public static final class Deadlocks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.DEADLOCKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectDeadlocks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new DeadlockDetector(), DetectorType.DEADLOCKS, "Deadlocks");
        }
    }

    public static final class Visibility implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VISIBILITY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVisibility; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VisibilityMonitor(), DetectorType.VISIBILITY, "Visibility");
        }
    }

    public static final class Livelocks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LIVELOCKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLivelocks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LivelockDetector(), DetectorType.LIVELOCKS, "Livelocks");
        }
    }

    // ---------- Phase 2 — Core advanced ----------

    public static final class FalseSharing implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FALSE_SHARING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFalseSharing; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FalseSharingDetector(), DetectorType.FALSE_SHARING, "FalseSharing");
        }
    }

    public static final class WakeupIssues implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.WAKEUP_ISSUES; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectWakeupIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new WakeupDetector(), DetectorType.WAKEUP_ISSUES, "WakeupIssues");
        }
    }

    public static final class ConstructorSafety implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONSTRUCTOR_SAFETY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.validateConstructorSafety; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ConstructorSafetyValidator(), DetectorType.CONSTRUCTOR_SAFETY, "ConstructorSafety");
        }
    }

    public static final class ABAProblem implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.ABA_PROBLEM; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectABAProblem; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ABAProblemDetector(), DetectorType.ABA_PROBLEM, "ABAProblem");
        }
    }

    public static final class LockOrder implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LOCK_ORDER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.validateLockOrder; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LockOrderValidator(), DetectorType.LOCK_ORDER, "LockOrder");
        }
    }

    public static final class Synchronizers implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SYNCHRONIZERS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.monitorSynchronizers; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SynchronizerMonitor(), DetectorType.SYNCHRONIZERS, "Synchronizers");
        }
    }

    public static final class ThreadPool implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_POOL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.monitorThreadPool; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadPoolMonitor(), DetectorType.THREAD_POOL, "ThreadPool");
        }
    }

    public static final class MemoryOrdering implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.MEMORY_ORDERING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectMemoryOrderingViolations; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new MemoryOrderingMonitor(), DetectorType.MEMORY_ORDERING, "MemoryOrdering");
        }
    }

    public static final class AsyncPipeline implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.ASYNC_PIPELINE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.monitorAsyncPipeline; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new PipelineMonitor(), DetectorType.ASYNC_PIPELINE, "AsyncPipeline");
        }
    }

    public static final class ReadWriteLockFairness implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.READ_WRITE_LOCK_FAIRNESS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.monitorReadWriteLockFairness; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ReadWriteLockMonitor(), DetectorType.READ_WRITE_LOCK_FAIRNESS, "ReadWriteLockFairness");
        }
    }

    // ---------- Phase 2 — Monitors ----------

    public static final class Semaphore implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SEMAPHORE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.monitorSemaphore; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SemaphoreMisuseDetector(), DetectorType.SEMAPHORE, "Semaphore");
        }
    }

    public static final class CompletableFutureExceptions implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCompletableFutureExceptions; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureExceptionDetector(), DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, "CompletableFutureExceptions");
        }
    }

    public static final class CompletableFutureCompletionLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCompletableFutureCompletionLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureCompletionLeakDetector(), DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, "CompletableFutureCompletionLeaks");
        }
    }

    public static final class VirtualThreadPinning implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VIRTUAL_THREAD_PINNING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVirtualThreadPinning; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VirtualThreadPinningDetector(), DetectorType.VIRTUAL_THREAD_PINNING, "VirtualThreadPinning");
        }
    }

    public static final class ThreadPoolDeadlock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_POOL_DEADLOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadPoolDeadlocks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadPoolDeadlockDetector(), DetectorType.THREAD_POOL_DEADLOCK, "ThreadPoolDeadlock");
        }
    }

    public static final class ConcurrentModifications implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONCURRENT_MODIFICATIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectConcurrentModifications; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ConcurrentModificationDetector(), DetectorType.CONCURRENT_MODIFICATIONS, "ConcurrentModifications");
        }
    }

    public static final class LockLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LOCK_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLockLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LockLeakDetector(), DetectorType.LOCK_LEAKS, "LockLeaks");
        }
    }

    public static final class SharedRandom implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_RANDOM; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedRandom; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedRandomDetector(), DetectorType.SHARED_RANDOM, "SharedRandom");
        }
    }

    public static final class BlockingQueue implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.BLOCKING_QUEUE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectBlockingQueueIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new BlockingQueueDetector(), DetectorType.BLOCKING_QUEUE, "BlockingQueue");
        }
    }

    public static final class ConditionVariables implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONDITION_VARIABLES; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectConditionVariableIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ConditionVariableDetector(), DetectorType.CONDITION_VARIABLES, "ConditionVariables");
        }
    }

    public static final class SimpleDateFormat_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SIMPLE_DATE_FORMAT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSimpleDateFormatIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SimpleDateFormatDetector(), DetectorType.SIMPLE_DATE_FORMAT, "SimpleDateFormat");
        }
    }

    public static final class ParallelStreams implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.PARALLEL_STREAMS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectParallelStreamIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ParallelStreamDetector(), DetectorType.PARALLEL_STREAMS, "ParallelStreams");
        }
    }

    public static final class ResourceLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.RESOURCE_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectResourceLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ResourceLeakDetector(), DetectorType.RESOURCE_LEAKS, "ResourceLeaks");
        }
    }

    // ---------- Phase 2 — Additional concurrency ----------

    public static final class CountDownLatch_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COUNTDOWN_LATCH; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCountDownLatchIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CountDownLatchDetector(), DetectorType.COUNTDOWN_LATCH, "CountDownLatch");
        }
    }

    public static final class CyclicBarrier_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CYCLIC_BARRIER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCyclicBarrierIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CyclicBarrierDetector(), DetectorType.CYCLIC_BARRIER, "CyclicBarrier");
        }
    }

    public static final class ReentrantLock_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.REENTRANT_LOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectReentrantLockIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ReentrantLockDetector(), DetectorType.REENTRANT_LOCK, "ReentrantLock");
        }
    }

    public static final class VolatileArray implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VOLATILE_ARRAY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVolatileArrayIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VolatileArrayDetector(), DetectorType.VOLATILE_ARRAY, "VolatileArray");
        }
    }

    public static final class DoubleCheckedLocking implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.DOUBLE_CHECKED_LOCKING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectDoubleCheckedLocking; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new DoubleCheckedLockingDetector(), DetectorType.DOUBLE_CHECKED_LOCKING, "DoubleCheckedLocking");
        }
    }

    public static final class WaitTimeout implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.WAIT_TIMEOUT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectWaitTimeout; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new WaitTimeoutDetector(), DetectorType.WAIT_TIMEOUT, "WaitTimeout");
        }
    }

    public static final class LockContention implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LOCK_CONTENTION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLockContention; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LockContentionDetector(), DetectorType.LOCK_CONTENTION, "LockContention");
        }
    }

    public static final class SynchronizedNonFinal implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SYNCHRONIZED_NON_FINAL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSynchronizedNonFinal; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SynchronizedNonFinalDetector(), DetectorType.SYNCHRONIZED_NON_FINAL, "SynchronizedNonFinal");
        }
    }

    public static final class MissedSignal implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.MISSED_SIGNAL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectMissedSignals; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new MissedSignalDetector(), DetectorType.MISSED_SIGNAL, "MissedSignal");
        }
    }

    public static final class LazyInitRace implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LAZY_INIT_RACE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLazyInitRace; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LazyInitRaceDetector(), DetectorType.LAZY_INIT_RACE, "LazyInitRace");
        }
    }

    // ---------- Phase 2 — Advanced concurrency utilities ----------

    public static final class Phaser_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.PHASER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectPhaserIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new PhaserDetector(), DetectorType.PHASER, "Phaser");
        }
    }

    public static final class StampedLock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STAMPED_LOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStampedLockIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StampedLockDetector(), DetectorType.STAMPED_LOCK, "StampedLock");
        }
    }

    public static final class Exchanger_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.EXCHANGER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectExchangerIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ExchangerDetector(), DetectorType.EXCHANGER, "Exchanger");
        }
    }

    public static final class ScheduledExecutor implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SCHEDULED_EXECUTOR; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectScheduledExecutorIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ScheduledExecutorDetector(), DetectorType.SCHEDULED_EXECUTOR, "ScheduledExecutor");
        }
    }

    public static final class ForkJoinPool implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FORK_JOIN_POOL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectForkJoinPoolIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ForkJoinPoolDetector(), DetectorType.FORK_JOIN_POOL, "ForkJoinPool");
        }
    }

    public static final class ThreadFactory_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_FACTORY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadFactoryIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadFactoryDetector(), DetectorType.THREAD_FACTORY, "ThreadFactory");
        }
    }

    // ---------- Phase 3 — Behavioral ----------

    public static final class RaceConditions implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.RACE_CONDITIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectRaceConditions; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new RaceConditionDetector(), DetectorType.RACE_CONDITIONS, "RaceConditions");
        }
    }

    public static final class ThreadLocalLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_LOCAL_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadLocalLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadLocalMonitor(), DetectorType.THREAD_LOCAL_LEAKS, "ThreadLocalLeaks");
        }
    }

    public static final class BusyWaiting implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.BUSY_WAITING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectBusyWaiting; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new BusyWaitDetector(), DetectorType.BUSY_WAITING, "BusyWaiting");
        }
    }

    public static final class AtomicityViolations implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.ATOMICITY_VIOLATIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectAtomicityViolations; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new AtomicityValidator(), DetectorType.ATOMICITY_VIOLATIONS, "AtomicityViolations");
        }
    }

    public static final class InterruptMishandling implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.INTERRUPT_MISHANDLING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectInterruptMishandling; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new InterruptMonitor(), DetectorType.INTERRUPT_MISHANDLING, "InterruptMishandling");
        }
    }

    // ---------- Phase 4 — Infrastructure & resource management ----------

    public static final class ThreadLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadLeakDetector(), DetectorType.THREAD_LEAKS, "ThreadLeaks");
        }
    }

    public static final class SleepInLock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SLEEP_IN_LOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSleepInLock; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SleepInLockDetector(), DetectorType.SLEEP_IN_LOCK, "SleepInLock");
        }
    }

    public static final class UnboundedQueue implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.UNBOUNDED_QUEUE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectUnboundedQueue; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new UnboundedQueueDetector(), DetectorType.UNBOUNDED_QUEUE, "UnboundedQueue");
        }
    }

    public static final class ThreadStarvation implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_STARVATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadStarvation; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadStarvationDetector(), DetectorType.THREAD_STARVATION, "ThreadStarvation");
        }
    }

    // ---------- Phase 5 — Thread-safety of common types ----------

    public static final class Calendar_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CALENDAR; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCalendarIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CalendarDetector(), DetectorType.CALENDAR, "Calendar");
        }
    }

    public static final class SharedCollections implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_COLLECTIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedCollections; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedCollectionDetector(), DetectorType.SHARED_COLLECTIONS, "SharedCollections");
        }
    }

    public static final class Timer_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.TIMER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectTimerIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new TimerDetector(), DetectorType.TIMER, "Timer");
        }
    }

    public static final class CopyOnWriteCollections implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COPY_ON_WRITE_COLLECTIONS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCopyOnWriteCollectionIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CopyOnWriteCollectionDetector(), DetectorType.COPY_ON_WRITE_COLLECTIONS, "CopyOnWriteCollections");
        }
    }

    public static final class StringBuilder_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STRING_BUILDER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStringBuilderIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StringBuilderDetector(), DetectorType.STRING_BUILDER, "StringBuilder");
        }
    }

    // ---------- Phase 6 — Virtual thread concurrency (Java 21+) ----------

    public static final class StructuredConcurrency implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STRUCTURED_CONCURRENCY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStructuredConcurrencyIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StructuredConcurrencyMisuseDetector(), DetectorType.STRUCTURED_CONCURRENCY, "StructuredConcurrency");
        }
    }

    public static final class VirtualThreadContextLeaks implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVirtualThreadContextLeaks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VirtualThreadContextLeakDetector(), DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, "VirtualThreadContextLeaks");
        }
    }

    public static final class ScopedValue_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SCOPED_VALUE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectScopedValueMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ScopedValueMisuseDetector(), DetectorType.SCOPED_VALUE, "ScopedValue");
        }
    }

    public static final class VirtualThreadCpuBound implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VIRTUAL_THREAD_CPU_BOUND; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVirtualThreadCpuBoundTasks; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VirtualThreadCpuBoundTaskDetector(), DetectorType.VIRTUAL_THREAD_CPU_BOUND, "VirtualThreadCpuBound");
        }
    }

    public static final class VirtualThreadCarrierExhaustion implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVirtualThreadCarrierExhaustion; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VirtualThreadCarrierExhaustionDetector(), DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION, "VirtualThreadCarrierExhaustion");
        }
    }

    // ---------- Phase 7 — High-level concurrency patterns ----------

    public static final class HttpClient_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.HTTP_CLIENT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectHttpClientIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new HttpClientConcurrencyDetector(), DetectorType.HTTP_CLIENT, "HttpClient");
        }
    }

    public static final class StreamClosing implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STREAM_CLOSING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStreamClosing; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StreamClosingDetector(), DetectorType.STREAM_CLOSING, "StreamClosing");
        }
    }

    public static final class CacheConcurrency implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CACHE_CONCURRENCY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCacheConcurrency; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CacheConcurrencyDetector(), DetectorType.CACHE_CONCURRENCY, "CacheConcurrency");
        }
    }

    public static final class CompletableFutureChain implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COMPLETABLEFUTURE_CHAIN; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCompletableFutureChainIssues; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureChainDetector(), DetectorType.COMPLETABLEFUTURE_CHAIN, "CompletableFutureChain");
        }
    }

    // ---------- Phase 8 — Lifecycle & structural correctness ----------

    public static final class ExecutorShutdown implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.EXECUTOR_SHUTDOWN; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectExecutorShutdown; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ExecutorShutdownDetector(), DetectorType.EXECUTOR_SHUTDOWN, "ExecutorShutdown");
        }
    }

    public static final class MutableMapKey implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.MUTABLE_MAP_KEY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectMutableMapKeys; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new MutableMapKeyDetector(), DetectorType.MUTABLE_MAP_KEY, "MutableMapKey");
        }
    }

    public static final class NestedMonitorLockout implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.NESTED_MONITOR_LOCKOUT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectNestedMonitorLockout; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new NestedMonitorLockoutDetector(), DetectorType.NESTED_MONITOR_LOCKOUT, "NestedMonitorLockout");
        }
    }

    public static final class LockDowngrade implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LOCK_DOWNGRADE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLockDowngrade; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LockDowngradeDetector(), DetectorType.LOCK_DOWNGRADE, "LockDowngrade");
        }
    }

    public static final class InheritableThreadLocal_ implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.INHERITABLE_THREAD_LOCAL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectInheritableThreadLocalMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new InheritableThreadLocalMisuseDetector(), DetectorType.INHERITABLE_THREAD_LOCAL, "InheritableThreadLocal");
        }
    }

    // ---------- Phase 9 — Repository & environment state ----------

    // ---------- Phase 10 — API traps & subtle concurrency bugs ----------

    public static final class ThreadLocalContamination implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_LOCAL_CONTAMINATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadLocalContamination; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadLocalContaminationDetector(), DetectorType.THREAD_LOCAL_CONTAMINATION, "ThreadLocalContamination");
        }
    }

    public static final class AtomicNonAtomicUpdate implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.ATOMIC_NON_ATOMIC_UPDATE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectAtomicNonAtomicUpdates; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new AtomicNonAtomicUpdateDetector(), DetectorType.ATOMIC_NON_ATOMIC_UPDATE, "AtomicNonAtomicUpdate");
        }
    }

    public static final class SynchronizedCollectionIteration implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SYNCHRONIZED_COLLECTION_ITERATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSynchronizedCollectionIteration; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SynchronizedCollectionIterationDetector(), DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, "SynchronizedCollectionIteration");
        }
    }

    public static final class SharedFormatter implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_FORMATTER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedFormatter; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedFormatterDetector(), DetectorType.SHARED_FORMATTER, "SharedFormatter");
        }
    }

    public static final class ConcurrentMapComputeRecursion implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectConcurrentMapComputeRecursion; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ConcurrentMapComputeRecursionDetector(), DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, "ConcurrentMapComputeRecursion");
        }
    }

    public static final class SynchronizedOnLiteral implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SYNCHRONIZED_ON_LITERAL; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSynchronizedOnLiteral; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SynchronizedOnLiteralDetector(), DetectorType.SYNCHRONIZED_ON_LITERAL, "SynchronizedOnLiteral");
        }
    }

    public static final class PublicLockExposure implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.PUBLIC_LOCK_EXPOSURE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectPublicLockExposure; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new PublicLockExposureDetector(), DetectorType.PUBLIC_LOCK_EXPOSURE, "PublicLockExposure");
        }
    }

    public static final class ForkJoinTaskBlocking implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FORK_JOIN_TASK_BLOCKING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectForkJoinTaskBlocking; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ForkJoinTaskBlockingDetector(), DetectorType.FORK_JOIN_TASK_BLOCKING, "ForkJoinTaskBlocking");
        }
    }

    public static final class OptimisticReadValidation implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.OPTIMISTIC_READ_VALIDATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectOptimisticReadValidation; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new OptimisticReadValidationDetector(), DetectorType.OPTIMISTIC_READ_VALIDATION, "OptimisticReadValidation");
        }
    }

    public static final class CfCommonPoolBlocking implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CF_COMMON_POOL_BLOCKING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCFCommonPoolBlocking; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureCommonPoolBlockingDetector(), DetectorType.CF_COMMON_POOL_BLOCKING, "CfCommonPoolBlocking");
        }
    }

    // ---------- Phase 11 — Thread-safety of additional types & patterns ----------

    public static final class SharedMatcher implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_MATCHER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedMatcher; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedMatcherDetector(), DetectorType.SHARED_MATCHER, "SharedMatcher");
        }
    }

    public static final class SharedDecimalFormat implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_DECIMAL_FORMAT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedDecimalFormat; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedDecimalFormatDetector(), DetectorType.SHARED_DECIMAL_FORMAT, "SharedDecimalFormat");
        }
    }

    public static final class WeakReferenceRace implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.WEAK_REFERENCE_RACE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectWeakReferenceRace; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new WeakReferenceRaceDetector(), DetectorType.WEAK_REFERENCE_RACE, "WeakReferenceRace");
        }
    }

    public static final class StatefulLambda implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STATEFUL_LAMBDA; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStatefulLambda; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StatefulLambdaDetector(), DetectorType.STATEFUL_LAMBDA, "StatefulLambda");
        }
    }

    // SHARED_MESSAGE_DIGEST intentionally omitted — see SharedMessageDigestDetectorFactory
    // for the dedicated typed adapter that surfaces structuredViolations directly.

    // ---------- Phase 12 — Operational & hygiene concurrency issues ----------

    public static final class InterruptSwallowing implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.INTERRUPT_SWALLOWING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectInterruptSwallowing; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new InterruptSwallowingDetector(), DetectorType.INTERRUPT_SWALLOWING, "InterruptSwallowing");
        }
    }

    public static final class MdcContextLeak implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.MDC_CONTEXT_LEAK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectMdcContextLeak; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new MdcContextLeakDetector(), DetectorType.MDC_CONTEXT_LEAK, "MdcContextLeak");
        }
    }

    public static final class SystemPropertyMutation implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SYSTEM_PROPERTY_MUTATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSystemPropertyMutation; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SystemPropertyMutationDetector(), DetectorType.SYSTEM_PROPERTY_MUTATION, "SystemPropertyMutation");
        }
    }

    public static final class FutureIgnored implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FUTURE_IGNORED; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFutureIgnored; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FutureIgnoredDetector(), DetectorType.FUTURE_IGNORED, "FutureIgnored");
        }
    }

    public static final class ExplicitGc implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.EXPLICIT_GC; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectExplicitGc; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ExplicitGcDetector(), DetectorType.EXPLICIT_GC, "ExplicitGc");
        }
    }

    public static final class DeprecatedThreadApi implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.DEPRECATED_THREAD_API; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectDeprecatedThreadApi; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new DeprecatedThreadApiDetector(), DetectorType.DEPRECATED_THREAD_API, "DeprecatedThreadApi");
        }
    }

    public static final class SharedXmlParser implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_XML_PARSER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedXmlParser; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedXmlParserDetector(), DetectorType.SHARED_XML_PARSER, "SharedXmlParser");
        }
    }

    public static final class BoxedPrimitiveLock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.BOXED_PRIMITIVE_LOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectBoxedPrimitiveLock; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new BoxedPrimitiveLockDetector(), DetectorType.BOXED_PRIMITIVE_LOCK, "BoxedPrimitiveLock");
        }
    }

    public static final class SharedTimeZone implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_TIMEZONE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedTimeZone; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedTimeZoneDetector(), DetectorType.SHARED_TIMEZONE, "SharedTimeZone");
        }
    }

    public static final class UncaughtExceptionHandler implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.UNCAUGHT_EXCEPTION_HANDLER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectUncaughtExceptionHandler; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new UncaughtExceptionHandlerDetector(), DetectorType.UNCAUGHT_EXCEPTION_HANDLER, "UncaughtExceptionHandler");
        }
    }

    // ---------- Phase 13 — additional concurrency-bug categories (1.0.0+) ----------

    public static final class DaemonThreadHygiene implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.DAEMON_THREAD_HYGIENE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectDaemonThreadHygiene; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new DaemonThreadHygieneDetector(), DetectorType.DAEMON_THREAD_HYGIENE, "DaemonThreadHygiene");
        }
    }

    public static final class NotifyWithoutMonitor implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.NOTIFY_WITHOUT_MONITOR; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectNotifyWithoutMonitor; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new NotifyWithoutMonitorDetector(), DetectorType.NOTIFY_WITHOUT_MONITOR, "NotifyWithoutMonitor");
        }
    }

    public static final class SharedSecureRandom implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_SECURE_RANDOM; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedSecureRandom; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedSecureRandomDetector(), DetectorType.SHARED_SECURE_RANDOM, "SharedSecureRandom");
        }
    }

    public static final class WeakHashMapShared implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.WEAK_HASH_MAP_SHARED; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectWeakHashMapShared; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new WeakHashMapSharedDetector(), DetectorType.WEAK_HASH_MAP_SHARED, "WeakHashMapShared");
        }
    }

    public static final class JdbcConnectionShared implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.JDBC_CONNECTION_SHARED; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectJdbcConnectionShared; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new JdbcConnectionSharedDetector(), DetectorType.JDBC_CONNECTION_SHARED, "JdbcConnectionShared");
        }
    }

    // ---------- Phase 14 — additional thread-unsafe primitives & publication hazards (1.7.0+) ----------

    public static final class SharedStatefulCrypto implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_STATEFUL_CRYPTO; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedStatefulCrypto; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedStatefulCryptoDetector(), DetectorType.SHARED_STATEFUL_CRYPTO, "SharedStatefulCrypto");
        }
    }

    public static final class ConcurrentMapCheckThenAct implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectConcurrentMapCheckThenAct; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new NonAtomicConcurrentMapUpdateDetector(), DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, "NonAtomicConcurrentMapUpdate");
        }
    }

    public static final class SharedDeflater implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_DEFLATER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedDeflater; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedDeflaterDetector(), DetectorType.SHARED_DEFLATER, "SharedDeflater");
        }
    }

    public static final class ThisEscape implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THIS_ESCAPE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThisEscape; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThisEscapeDetector(), DetectorType.THIS_ESCAPE, "ThisEscape");
        }
    }

    public static final class ThreadLocalRandomMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.THREAD_LOCAL_RANDOM_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectThreadLocalRandomMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ThreadLocalRandomMisuseDetector(), DetectorType.THREAD_LOCAL_RANDOM_MISUSE, "ThreadLocalRandomMisuse");
        }
    }

    public static final class CompletableFutureObtrudeAbuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCompletableFutureObtrudeAbuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureObtrudeDetector(), DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE, "CompletableFutureObtrude");
        }
    }

    public static final class SpuriousWakeupHazard implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SPURIOUS_WAKEUP_HAZARD; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSpuriousWakeupHazard; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SpuriousWakeupDetector(), DetectorType.SPURIOUS_WAKEUP_HAZARD, "SpuriousWakeup");
        }
    }

    public static final class LockUpgradeDeadlock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LOCK_UPGRADE_DEADLOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLockUpgradeDeadlock; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LockUpgradeDeadlockDetector(), DetectorType.LOCK_UPGRADE_DEADLOCK, "LockUpgradeDeadlock");
        }
    }

    public static final class TryLockMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.TRY_LOCK_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectTryLockMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new TryLockMisuseDetector(), DetectorType.TRY_LOCK_MISUSE, "TryLockMisuse");
        }
    }

    public static final class CompletableFutureBlockingCallback implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectCFBlockingCallback; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new CompletableFutureBlockingCallbackDetector(), DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, "CompletableFutureBlockingCallback");
        }
    }

    // ---- Phase 16: JDK 25/26 preview-era concurrency detectors ----

    public static final class StableValueMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STABLE_VALUE_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStableValueMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StableValueMisuseDetector(), DetectorType.STABLE_VALUE_MISUSE, "StableValueMisuse");
        }
    }

    public static final class StructuredTaskScopeMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STRUCTURED_TASK_SCOPE_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStructuredTaskScopeMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StructuredTaskScopeMisuseDetector(), DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, "StructuredTaskScopeMisuse");
        }
    }

    public static final class GathererConcurrencyMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.GATHERER_CONCURRENCY_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectGathererConcurrencyMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new GathererConcurrencyMisuseDetector(), DetectorType.GATHERER_CONCURRENCY_MISUSE, "GathererConcurrencyMisuse");
        }
    }

    // ---- Phase 17: Shared stateful JDK objects, I/O position races & contention advisories ----

    public static final class SharedByteBuffer implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_BYTE_BUFFER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedByteBuffer; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedByteBufferDetector(), DetectorType.SHARED_BYTE_BUFFER, "SharedByteBuffer");
        }
    }

    public static final class SharedCharsetCoder implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_CHARSET_CODER; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedCharsetCoder; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedCharsetCoderDetector(), DetectorType.SHARED_CHARSET_CODER, "SharedCharsetCoder");
        }
    }

    public static final class SharedChecksum implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_CHECKSUM; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedChecksum; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedChecksumDetector(), DetectorType.SHARED_CHECKSUM, "SharedChecksum");
        }
    }

    public static final class FileChannelPositionRace implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FILE_CHANNEL_POSITION_RACE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFileChannelPositionRace; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FileChannelPositionRaceDetector(), DetectorType.FILE_CHANNEL_POSITION_RACE, "FileChannelPositionRace");
        }
    }

    public static final class SharedIterator implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_ITERATOR; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedIterator; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedIteratorDetector(), DetectorType.SHARED_ITERATOR, "SharedIterator");
        }
    }

    public static final class HighContentionAtomic implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.HIGH_CONTENTION_ATOMIC; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectHighContentionAtomic; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new HighContentionAtomicDetector(), DetectorType.HIGH_CONTENTION_ATOMIC, "HighContentionAtomic");
        }
    }

    public static final class SharedJsonMapperReconfig implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_JSON_MAPPER_RECONFIG; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedJsonMapperReconfig; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedJsonMapperReconfigDetector(), DetectorType.SHARED_JSON_MAPPER_RECONFIG, "SharedJsonMapperReconfig");
        }
    }

    public static final class LazyConstantMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LAZY_CONSTANT_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLazyConstantMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LazyConstantMisuseDetector(), DetectorType.LAZY_CONSTANT_MISUSE, "LazyConstantMisuse");
        }
    }

    public static final class FinalFieldMutation implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FINAL_FIELD_MUTATION; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFinalFieldMutation; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FinalFieldMutationDetector(), DetectorType.FINAL_FIELD_MUTATION, "FinalFieldMutation");
        }
    }

    public static final class SharedKdf implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_KDF; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedKdf; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedKdfDetector(), DetectorType.SHARED_KDF, "SharedKdf");
        }
    }

    // ---------- Executor / future / latch ----------

    public static final class LatchMisuse implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.LATCH_MISUSE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectLatchMisuse; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new LatchMisuseDetector(), DetectorType.LATCH_MISUSE, "LatchMisuse");
        }
    }

    public static final class ExecutorDeadlock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.EXECUTOR_DEADLOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectExecutorDeadlock; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ExecutorDeadlockDetector(), DetectorType.EXECUTOR_DEADLOCK, "ExecutorDeadlock");
        }
    }

    public static final class FutureBlocking implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FUTURE_BLOCKING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFutureBlocking; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FutureBlockingDetector(), DetectorType.FUTURE_BLOCKING, "FutureBlocking");
        }
    }

    public static final class FlowPublisherConcurrency implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.FLOW_PUBLISHER_CONCURRENCY; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectFlowPublisherConcurrency; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new FlowPublisherConcurrencyDetector(), DetectorType.FLOW_PUBLISHER_CONCURRENCY, "FlowPublisherConcurrency");
        }
    }

    public static final class ConfinedArenaThreadEscape implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.CONFINED_ARENA_THREAD_ESCAPE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectConfinedArenaThreadEscape; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new ConfinedArenaThreadEscapeDetector(), DetectorType.CONFINED_ARENA_THREAD_ESCAPE, "ConfinedArenaThreadEscape");
        }
    }

    public static final class SharedMemorySegmentRace implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_MEMORY_SEGMENT_RACE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedMemorySegmentRace; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedMemorySegmentRaceDetector(), DetectorType.SHARED_MEMORY_SEGMENT_RACE, "SharedMemorySegmentRace");
        }
    }

    public static final class VarHandleNonAtomicUpdate implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVarHandleNonAtomicUpdate; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VarHandleNonAtomicUpdateDetector(), DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, "VarHandleNonAtomicUpdate");
        }
    }

    public static final class RecordMutableComponentLeak implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.RECORD_MUTABLE_COMPONENT_LEAK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectRecordMutableComponentLeak; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new RecordMutableComponentLeakDetector(), DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, "RecordMutableComponentLeak");
        }
    }

    public static final class StaticInitDeadlock implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.STATIC_INIT_DEADLOCK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectStaticInitDeadlock; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new StaticInitDeadlockDetector(), DetectorType.STATIC_INIT_DEADLOCK, "StaticInitDeadlock");
        }
    }

    public static final class VirtualThreadPooling implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.VIRTUAL_THREAD_POOLING; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectVirtualThreadPooling; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new VirtualThreadPoolingDetector(), DetectorType.VIRTUAL_THREAD_POOLING, "VirtualThreadPooling");
        }
    }

    public static final class PlatformThreadPerTask implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.PLATFORM_THREAD_PER_TASK; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectPlatformThreadPerTask; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new PlatformThreadPerTaskDetector(), DetectorType.PLATFORM_THREAD_PER_TASK, "PlatformThreadPerTask");
        }
    }

    public static final class SharedSplittableRandom implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.SHARED_SPLITTABLE_RANDOM; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectSharedSplittableRandom; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new SharedSplittableRandomDetector(), DetectorType.SHARED_SPLITTABLE_RANDOM, "SharedSplittableRandom");
        }
    }
}
