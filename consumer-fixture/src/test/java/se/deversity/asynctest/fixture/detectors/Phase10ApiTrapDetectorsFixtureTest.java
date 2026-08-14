package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 10, API-trap group — {@code THREAD_LOCAL_CONTAMINATION} through
 * {@code CF_COMMON_POOL_BLOCKING}.
 *
 * <p>Corresponding examples: {@code examples/85-thread-local-contamination},
 * {@code examples/99-atomic-non-atomic},
 * {@code examples/80-synchronized-collection-iteration},
 * {@code examples/71-shared-formatter}, {@code examples/40-concurrent-map-recursion},
 * {@code examples/82-synchronized-on-literal}, {@code examples/65-public-lock-exposure},
 * {@code examples/51-fork-join-task-blocking},
 * {@code examples/62-optimistic-read-validation},
 * {@code examples/38-cf-common-pool-blocking}.
 */
class Phase10ApiTrapDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "ThreadLocalContaminationDetector",
                    "AtomicNonAtomicUpdateDetector",
                    "SynchronizedCollectionIterationDetector",
                    "SharedFormatterDetector",
                    "ConcurrentMapComputeRecursionDetector",
                    "SynchronizedOnLiteralDetector",
                    "PublicLockExposureDetector",
                    "ForkJoinTaskBlockingDetector",
                    "OptimisticReadValidationDetector",
                    "CompletableFutureCommonPoolBlockingDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LOCAL_CONTAMINATION})
    void threadLocalContamination() {
        reachable("threadLocalContaminationDetector()",
            AsyncTestContext::threadLocalContaminationDetector);

        // State left behind on a pooled thread contaminates the next task that lands on it.
        // A pooled thread that starts a new task while still carrying the previous task's
        // ThreadLocal is contaminated - the second task silently reads the first one's state.
        var contamination = AsyncTestContext.threadLocalContaminationDetector();
        contamination.recordNewTask(Thread.currentThread(), "task-1");
        POOLED_STATE.set("task-scoped");
        contamination.recordSet(Thread.currentThread(), POOLED_STATE, "pooled-state");
        try {
            spin(32);
            contamination.recordNewTask(Thread.currentThread(), "task-2");
            contamination.recordGet(Thread.currentThread(), POOLED_STATE, "pooled-state", true);
        } finally {
            POOLED_STATE.remove();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ATOMIC_NON_ATOMIC_UPDATE})
    void atomicNonAtomicUpdate() {
        reachable("atomicNonAtomicUpdateDetector()",
            AsyncTestContext::atomicNonAtomicUpdateDetector);

        // get() then set() is two operations; only updateAndGet() is one.
        // get() then set() on an atomic is a lost update: each call is atomic, the pair is
        // not. Shared across the round, since a fixture-local atomic races with nobody.
        var atomicDetector = AsyncTestContext.atomicNonAtomicUpdateDetector();
        atomicDetector.recordGet(SHARED_COUNTER, "shared-counter", Thread.currentThread());
        int current = SHARED_COUNTER.get();
        SHARED_COUNTER.set(current + 1);
        atomicDetector.recordSet(SHARED_COUNTER, "shared-counter", Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_COLLECTION_ITERATION})
    void synchronizedCollectionIteration() {
        reachable("synchronizedCollectionIterationDetector()",
            AsyncTestContext::synchronizedCollectionIterationDetector);

        // Collections.synchronizedList synchronises each call, not the iteration —
        // iterating without the explicit synchronized block is the documented trap.
        // Collections.synchronizedList makes each call atomic but not iteration: the loop
        // must hold the wrapper's own monitor. holdingLock=false is the trap the detector
        // reports - a consumer iterating without the synchronized block around it.
        var iterationDetector = AsyncTestContext.synchronizedCollectionIterationDetector();
        iterationDetector.recordWrapperCreated(SHARED_SYNC_LIST, "shared-sync-list");
        iterationDetector.recordIterationStarted(SHARED_SYNC_LIST, Thread.currentThread(), false);
        synchronized (SHARED_SYNC_LIST) {
            int total = 0;
            for (int value : SHARED_SYNC_LIST) {
                total += value;
            }
            spin(total);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_FORMATTER})
    void sharedFormatter() {
        reachable("sharedFormatterDetector()", AsyncTestContext::sharedFormatterDetector);

        // java.util.Formatter is explicitly not thread-safe; this one is shared.
        AsyncTestContext.sharedFormatterDetector()
                .recordAccess(SHARED_FORMATTER, "shared-formatter", Thread.currentThread());
        try {
            synchronized (SHARED_FORMATTER) {
                SHARED_FORMATTER.format("%d;", 1);
            }
        } catch (RuntimeException expected) {
            // A shared Formatter losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION})
    void concurrentMapComputeRecursion() {
        reachable("concurrentMapComputeRecursionDetector()",
            AsyncTestContext::concurrentMapComputeRecursionDetector);

        // Recursive update inside computeIfAbsent can deadlock a ConcurrentHashMap bin;
        // the fixture keeps the mapping function free of further map access.
        // A mapping function that touches the same map re-enters a bin the map still has
        // locked, which can deadlock the map permanently. The nested compute is recorded
        // rather than performed for exactly that reason.
        var recursionDetector = AsyncTestContext.concurrentMapComputeRecursionDetector();
        recursionDetector.recordComputeStart(SHARED_COMPUTE_MAP, "k", Thread.currentThread(),
                "shared-compute-map");
        SHARED_COMPUTE_MAP.computeIfAbsent("k", key -> {
            // The SAME map, key and thread: re-entering a compute already in progress is the
            // recursion, and the detector keys the slot on all three. A nested compute for a
            // different key is just a second compute and says nothing.
            recursionDetector.recordComputeStart(SHARED_COMPUTE_MAP, "k",
                    Thread.currentThread(), "shared-compute-map");
            return key.length();
        });
        recursionDetector.recordComputeEnd(SHARED_COMPUTE_MAP, "k", Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_ON_LITERAL})
    void synchronizedOnLiteral() {
        reachable("synchronizedOnLiteralDetector()",
            AsyncTestContext::synchronizedOnLiteralDetector);

        // Interned literals and boxed constants are JVM-wide monitors — unrelated code
        // locking the same literal contends with this block.
        // A string literal is interned JVM-wide, so this monitor is shared with every other
        // piece of code in the process that happens to lock on the same text.
        AsyncTestContext.synchronizedOnLiteralDetector()
                .recordMonitorAcquired(INTERNED_LITERAL, Thread.currentThread(),
                        "Phase10.synchronizedOnLiteral");
        synchronized (INTERNED_LITERAL) {
            spin(32);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.PUBLIC_LOCK_EXPOSURE})
    void publicLockExposure() {
        reachable("publicLockExposureDetector()", AsyncTestContext::publicLockExposureDetector);

        // A lock reachable from outside the class can be held by code that knows nothing
        // about this class's invariants.
        // A lock reachable from outside the class that owns it can be taken by anyone, so
        // the owner's invariants are no longer the owner's to keep.
        // The finding needs ONE object that is both published and locked on: the detector
        // intersects the two sets by identity. Publishing the lock while synchronizing on its
        // owner records two different objects and intersects to nothing.
        var exposureDetector = AsyncTestContext.publicLockExposureDetector();
        ExposedLock exposed = SHARED_EXPOSED;
        exposureDetector.recordObjectPublished(exposed.lock, "ExposedLock.lock is public");
        exposureDetector.recordSynchronizedOnThis(exposed.lock, Thread.currentThread(),
                "ExposedLock");
        exposed.lock.lock();
        try {
            spin(32);
        } finally {
            exposed.lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FORK_JOIN_TASK_BLOCKING})
    void forkJoinTaskBlocking() {
        reachable("forkJoinTaskBlockingDetector()",
            AsyncTestContext::forkJoinTaskBlockingDetector);

        // Blocking inside a ForkJoinPool worker starves the pool; the fixture keeps the
        // task non-blocking and lets the detector speak to the shape.
        // Blocking inside a ForkJoinTask starves the pool: the worker is occupied instead of
        // stealing, and with enough of them the pool stops making progress altogether.
        var fjDetector = AsyncTestContext.forkJoinTaskBlockingDetector();
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            pool.submit(() -> {
                fjDetector.recordForkJoinTaskEntered(Thread.currentThread());
                fjDetector.recordBlockingCallAttempted(Thread.currentThread(), "Future.get");
                spin(64);
                fjDetector.recordForkJoinTaskExited(Thread.currentThread());
            }).join();
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.OPTIMISTIC_READ_VALIDATION})
    void optimisticReadValidation() {
        reachable("optimisticReadValidationDetector()",
            AsyncTestContext::optimisticReadValidationDetector);

        // tryOptimisticRead is only safe if validate() is checked — the fixture checks it.
        // An optimistic read is only a read if validate() is consulted before the data is
        // used. The detector reports data accessed under a stamp that was never validated,
        // which is what the ordering below records.
        var optimisticDetector = AsyncTestContext.optimisticReadValidationDetector();
        StampedLock lock = SHARED_STAMPED_LOCK;
        long stamp = lock.tryOptimisticRead();
        optimisticDetector.recordOptimisticReadStarted(lock, stamp, Thread.currentThread());
        int snapshot = spin(32);
        optimisticDetector.recordDataAccessed(lock, stamp, Thread.currentThread(), "snapshot");
        if (!lock.validate(stamp)) {
            long readStamp = lock.readLock();
            try {
                snapshot = spin(32);
            } finally {
                lock.unlockRead(readStamp);
            }
        }
        spin(snapshot % 8);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CF_COMMON_POOL_BLOCKING})
    void cfCommonPoolBlocking() {
        reachable("cfCommonPoolBlockingDetector()",
            AsyncTestContext::cfCommonPoolBlockingDetector);

        // supplyAsync with no executor runs on the shared common pool; blocking there is
        // the hazard, so this stage stays CPU-only and short.
        // join() on the common pool blocks one of a handful of threads the whole JVM shares,
        // and the task being waited on may be queued behind the very thread that is waiting.
        var commonPoolDetector = AsyncTestContext.cfCommonPoolBlockingDetector();
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> spin(64));
        commonPoolDetector.recordCommonPoolSubmission(future, Thread.currentThread(),
                "supplyAsync");
        commonPoolDetector.recordBlockingCall(future, Thread.currentThread(), "join");
        future.join();
    }

    private static final ThreadLocal<String> POOLED_STATE = new ThreadLocal<>();

    private static final Formatter SHARED_FORMATTER = new Formatter(new StringBuilder());

    private static final String INTERNED_LITERAL = "async-test-lib-literal-monitor";

    /** A lock a caller outside the class can acquire — the exposure. */
    private static final class ExposedLock {
        final ReentrantLock lock = new ReentrantLock();
    }

    private static final AtomicInteger SHARED_COUNTER = new AtomicInteger();

    private static final List<Integer> SHARED_SYNC_LIST =
        Collections.synchronizedList(new ArrayList<>(List.of(1, 2, 3)));

    private static final ConcurrentMap<String, Integer> SHARED_COMPUTE_MAP =
        new ConcurrentHashMap<>();

    private static final StampedLock SHARED_STAMPED_LOCK = new StampedLock();

    private static final ExposedLock SHARED_EXPOSED = new ExposedLock();
}
