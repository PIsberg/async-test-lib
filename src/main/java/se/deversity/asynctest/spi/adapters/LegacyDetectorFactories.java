package se.deversity.asynctest.spi.adapters;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.*;
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

    public static final class UncommittedChanges implements DetectorFactory {
        @Override public DetectorType type() { return DetectorType.UNCOMMITTED_CHANGES; }
        @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.detectUncommittedChanges; }
        @Override public Detector create(AsyncTestConfig c) {
            return new LegacyDetectorAdapter<>(new UncommittedChangesDetector(), DetectorType.UNCOMMITTED_CHANGES, "UncommittedChanges");
        }
    }

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
}
