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
        POOLED_STATE.set("task-scoped");
        try {
            spin(32);
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
        AtomicInteger counter = new AtomicInteger();
        counter.set(counter.get() + 1);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_COLLECTION_ITERATION})
    void synchronizedCollectionIteration() {
        reachable("synchronizedCollectionIterationDetector()",
            AsyncTestContext::synchronizedCollectionIterationDetector);

        // Collections.synchronizedList synchronises each call, not the iteration —
        // iterating without the explicit synchronized block is the documented trap.
        List<Integer> list = Collections.synchronizedList(new ArrayList<>(List.of(1, 2, 3)));
        synchronized (list) {
            int total = 0;
            for (int value : list) {
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
        ConcurrentMap<String, Integer> map = new ConcurrentHashMap<>();
        map.computeIfAbsent("k", key -> key.length());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_ON_LITERAL})
    void synchronizedOnLiteral() {
        reachable("synchronizedOnLiteralDetector()",
            AsyncTestContext::synchronizedOnLiteralDetector);

        // Interned literals and boxed constants are JVM-wide monitors — unrelated code
        // locking the same literal contends with this block.
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
        ExposedLock exposed = new ExposedLock();
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
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            pool.submit(() -> { spin(64); }).join();
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
        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        int snapshot = spin(32);
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
        CompletableFuture.supplyAsync(() -> spin(64)).join();
    }

    private static final ThreadLocal<String> POOLED_STATE = new ThreadLocal<>();

    private static final Formatter SHARED_FORMATTER = new Formatter(new StringBuilder());

    private static final String INTERNED_LITERAL = "async-test-lib-literal-monitor";

    /** A lock a caller outside the class can acquire — the exposure. */
    private static final class ExposedLock {
        final ReentrantLock lock = new ReentrantLock();
    }
}
