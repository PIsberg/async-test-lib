package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 2, monitor group — {@code SEMAPHORE} through {@code RESOURCE_LEAKS}.
 *
 * <p>Corresponding examples: {@code examples/70-semaphore-misuse},
 * {@code examples/01-completablefuture-exception-handling},
 * {@code examples/39-cf-completion-leak}, {@code examples/92-virtual-thread-pinning},
 * {@code examples/86-thread-pool-deadlock}, {@code examples/41-concurrent-modification},
 * {@code examples/57-lock-leak}, {@code examples/72-shared-random},
 * {@code examples/34-blocking-queue}, {@code examples/42-condition-variable},
 * {@code examples/73-simple-date-format}, {@code examples/63-parallel-stream},
 * {@code examples/67-resource-leak}.
 */
class Phase02MonitorDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "SemaphoreMisuseDetector",
                    "CompletableFutureExceptionDetector",
                    "CompletableFutureCompletionLeakDetector",
                    "VirtualThreadPinningDetector",
                    "ThreadPoolDeadlockDetector",
                    "ConcurrentModificationDetector",
                    "LockLeakDetector",
                    "SharedRandomDetector",
                    "BlockingQueueDetector",
                    "ConditionVariableDetector",
                    "SimpleDateFormatDetector",
                    "ParallelStreamDetector",
                    "ResourceLeakDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SEMAPHORE})
    void semaphore() {
        reachable("semaphoreMisuseDetector()", AsyncTestContext::semaphoreMisuseDetector);

        Semaphore permits = new Semaphore(1);
        if (permits.tryAcquire()) {
            try {
                spin(32);
            } finally {
                permits.release();
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS})
    void completableFutureExceptions() {
        reachable("completableFutureExceptionDetector()",
            AsyncTestContext::completableFutureExceptionDetector);

        // thenApply with no exceptionally/handle downstream: the swallowed-failure shape.
        CompletableFuture<String> failed =
            CompletableFuture.<String>failedFuture(new IllegalStateException("boom"))
                .exceptionally(t -> "recovered");
        failed.join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS})
    void completableFutureCompletionLeaks() {
        reachable("completableFutureCompletionLeakDetector()",
            AsyncTestContext::completableFutureCompletionLeakDetector);

        // Created, never completed by anyone: the leak the detector looks for.
        CompletableFuture<String> orphan = new CompletableFuture<>();
        orphan.complete("closed by the fixture so the round cannot hang");
        orphan.join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.VIRTUAL_THREAD_PINNING})
    void virtualThreadPinning() {
        reachable("virtualThreadPinningDetector()", AsyncTestContext::virtualThreadPinningDetector);

        // synchronized inside a virtual thread carrier is the classic pinning shape.
        Object monitor = new Object();
        synchronized (monitor) {
            spin(64);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_POOL_DEADLOCK})
    void threadPoolDeadlock() {
        reachable("threadPoolDeadlockDetector()", AsyncTestContext::threadPoolDeadlockDetector);

        // A task submitted to a single-thread pool that itself waits on the pool is the
        // deadlock shape; the fixture keeps the inner work independent so nothing hangs.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> { spin(32); }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Not the subject of this fixture.
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONCURRENT_MODIFICATIONS})
    void concurrentModifications() {
        reachable("concurrentModificationDetector()",
            AsyncTestContext::concurrentModificationDetector);

        List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
        list.add(4);
        int total = 0;
        for (int value : list) {
            total += value;
        }
        spin(total);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_LEAKS})
    void lockLeaks() {
        reachable("lockLeakDetector()", AsyncTestContext::lockLeakDetector);

        // lock() outside try is the shape that leaks on exception; here it is balanced.
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            spin(32);
        } finally {
            lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_RANDOM})
    void sharedRandom() {
        reachable("sharedRandomDetector()", AsyncTestContext::sharedRandomDetector);

        // One Random instance shared across the colliding workers.
        SHARED.nextInt(100);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.BLOCKING_QUEUE})
    void blockingQueue() {
        reachable("blockingQueueDetector()", AsyncTestContext::blockingQueueDetector);

        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(4);
        try {
            queue.offer(1, 50, TimeUnit.MILLISECONDS);
            queue.poll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONDITION_VARIABLES})
    void conditionVariables() {
        reachable("conditionVariableDetector()", AsyncTestContext::conditionVariableDetector);

        ReentrantLock lock = new ReentrantLock();
        Condition ready = lock.newCondition();
        lock.lock();
        try {
            ready.await(1, TimeUnit.MILLISECONDS);   // always timed
            ready.signalAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SIMPLE_DATE_FORMAT})
    void simpleDateFormat() {
        reachable("simpleDateFormatDetector()", AsyncTestContext::simpleDateFormatDetector);

        // The canonical thread-unsafe formatter, shared across workers. Concurrent use can
        // corrupt its internal calendar and throw — that is the bug being demonstrated, so
        // the fixture reports it through the detector rather than failing the round.
        try {
            SHARED_FORMAT.format(new Date(0L));
        } catch (RuntimeException expected) {
            // SimpleDateFormat losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.PARALLEL_STREAMS})
    void parallelStreams() {
        reachable("parallelStreamDetector()", AsyncTestContext::parallelStreamDetector);

        // A parallel stream inside an already-parallel test: common-pool contention.
        IntStream.range(0, 32).parallel().sum();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.RESOURCE_LEAKS})
    void resourceLeaks() {
        reachable("resourceLeakDetector()", AsyncTestContext::resourceLeakDetector);

        try (InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3})) {
            in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("in-memory stream must not fail", e);
        }
    }

    /** Deliberately shared across the colliding workers — that is the hazard. */
    private static final Random SHARED = new Random(42);

    /** Deliberately shared and deliberately not thread-safe. */
    private static final SimpleDateFormat SHARED_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
}
