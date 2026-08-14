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

        // More releases than acquires quietly inflates the permit count, so the semaphore
        // stops bounding anything at all.
        var semaphoreDetector = AsyncTestContext.semaphoreMisuseDetector();
        Semaphore permits = SHARED_PERMITS;
        semaphoreDetector.registerSemaphore(permits, "shared-permits", 1);
        semaphoreDetector.recordRelease(permits, "shared-permits");   // never acquired
        if (permits.tryAcquire()) {
            semaphoreDetector.recordAcquire(permits, "shared-permits");
            try {
                spin(32);
            } finally {
                permits.release();
                semaphoreDetector.recordRelease(permits, "shared-permits");
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS})
    void completableFutureExceptions() {
        reachable("completableFutureExceptionDetector()",
            AsyncTestContext::completableFutureExceptionDetector);

        // thenApply with no exceptionally/handle downstream: the swallowed-failure shape.
        // A future that completes exceptionally with nothing attached to handle it loses the
        // exception entirely - no stack trace, no failed test, just a value that never arrives.
        var cfExceptions = AsyncTestContext.completableFutureExceptionDetector();
        CompletableFuture<String> failed =
            CompletableFuture.<String>failedFuture(new IllegalStateException("boom"))
                .exceptionally(t -> "recovered");
        cfExceptions.recordFutureCreated(failed, "unhandled-future");
        cfExceptions.recordFutureCompleted(failed, "unhandled-future", false);
        failed.join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS})
    void completableFutureCompletionLeaks() {
        reachable("completableFutureCompletionLeakDetector()",
            AsyncTestContext::completableFutureCompletionLeakDetector);

        // Created, never completed by anyone: the leak the detector looks for.
        // A future created and never completed leaves whoever is waiting on it waiting for
        // good. The fixture completes it so the round cannot hang; the detector's subject is
        // the one that was never recorded as completed.
        // The leak is a future that is never completed at all. Recording one and then
        // completing it describes the healthy case, which is why the earlier version of this
        // fixture reported nothing.
        //
        // Nothing waits on the orphan below, so leaving it uncompleted costs the round
        // nothing - which is exactly why this leak is so easy to ship: the code that forgets
        // to complete a future does not hang, it just never finishes the work.
        var leakDetector = AsyncTestContext.completableFutureCompletionLeakDetector();
        CompletableFuture<String> orphan = new CompletableFuture<>();
        leakDetector.recordFutureCreated(orphan, "orphan-future");

        CompletableFuture<String> completed = new CompletableFuture<>();
        leakDetector.recordFutureCreated(completed, "completed-future");
        completed.complete("this one is closed properly");
        leakDetector.recordFutureCompleted(completed, "completed-future");
        completed.join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.VIRTUAL_THREAD_PINNING})
    void virtualThreadPinning() {
        reachable("virtualThreadPinningDetector()", AsyncTestContext::virtualThreadPinningDetector);

        // synchronized inside a virtual thread carrier is the classic pinning shape.
        // Blocking inside a monitor pins the virtual thread to its carrier. NATIVE is used
        // rather than a monitor cause because JEP 491 made synchronized non-pinning from JDK
        // 24, and PinningReport.hasIssues() correctly drops causes that no longer pin - so a
        // monitor-caused event would assert a finding that should not exist on a modern JDK.
        var pinningDetector = AsyncTestContext.virtualThreadPinningDetector();
        pinningDetector.startMonitoring();
        Object monitor = new Object();
        synchronized (monitor) {
            pinningDetector.recordPinningEvent(Thread.currentThread(), "native JNI downcall");
            spin(64);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_POOL_DEADLOCK})
    void threadPoolDeadlock() {
        reachable("threadPoolDeadlockDetector()", AsyncTestContext::threadPoolDeadlockDetector);

        // A task submitted to a single-thread pool that itself waits on the pool is the
        // deadlock shape; the fixture keeps the inner work independent so nothing hangs.
        // A task submitted from inside the pool that is running it waits for a worker that
        // cannot be freed until the waiter returns.
        var poolDeadlock = AsyncTestContext.threadPoolDeadlockDetector();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        poolDeadlock.registerPool(pool, "fixture-single-thread-pool");
        try {
            poolDeadlock.recordNestedSubmission(pool, "fixture-single-thread-pool");
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

        // Modifying a collection while another thread iterates it is what
        // ConcurrentModificationException is named for; the detector reports the overlap
        // rather than waiting for the throw, which only one of the two threads ever sees.
        var cmDetector = AsyncTestContext.concurrentModificationDetector();
        cmDetector.registerCollection(SHARED_LIST, "shared-list");
        cmDetector.recordIterationStarted(SHARED_LIST, "shared-list");
        cmDetector.recordModification(SHARED_LIST, "shared-list", "add");
        int total = 0;
        synchronized (SHARED_LIST) {
            for (int value : SHARED_LIST) {
                total += value;
            }
        }
        cmDetector.recordIterationEnded(SHARED_LIST, "shared-list");
        spin(total);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_LEAKS})
    void lockLeaks() {
        reachable("lockLeakDetector()", AsyncTestContext::lockLeakDetector);

        // lock() outside try is the shape that leaks on exception; here it is balanced.
        // A lock acquired and never released is held for the rest of the run, and every
        // other worker queues behind it forever. The fixture releases its own lock - the leak
        // is recorded against a separate one so nothing is actually left held.
        var lockLeak = AsyncTestContext.lockLeakDetector();
        lockLeak.registerLock(LEAKED_LOCK, "leaked-lock");
        lockLeak.recordLockAcquired(LEAKED_LOCK, "leaked-lock");   // no matching release
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
        // One Random shared by every worker: java.util.Random is thread-safe but its seed is
        // a single contended atomic, and a shared seed means correlated sequences.
        AsyncTestContext.sharedRandomDetector()
                .recordRandomAccess(SHARED, "shared-random", "nextInt");
        SHARED.nextInt(100);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.BLOCKING_QUEUE})
    void blockingQueue() {
        reachable("blockingQueueDetector()", AsyncTestContext::blockingQueueDetector);

        // A bounded queue whose offers time out is a producer that silently drops work, and
        // a poll that times out is a consumer that gave up on a queue that never filled.
        var queueDetector = AsyncTestContext.blockingQueueDetector();
        ArrayBlockingQueue<Integer> queue = SHARED_QUEUE;
        queueDetector.registerQueue(queue, "shared-queue", 1);
        try {
            boolean offered = queue.offer(1, 50, TimeUnit.MILLISECONDS);
            queueDetector.recordOffer(queue, "shared-queue", offered);
            queueDetector.recordOffer(queue, "shared-queue", false);   // the dropped item
            Integer taken = queue.poll(50, TimeUnit.MILLISECONDS);
            queueDetector.recordPoll(queue, "shared-queue", taken != null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONDITION_VARIABLES})
    void conditionVariables() {
        reachable("conditionVariableDetector()", AsyncTestContext::conditionVariableDetector);

        // A signal with nobody awaiting is lost, and an await that times out is a waiter
        // that gave up - the detector pairs the two to spot conditions nobody ever signals.
        var conditionDetector = AsyncTestContext.conditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition ready = lock.newCondition();
        conditionDetector.registerCondition(ready, "fixture-condition");
        lock.lock();
        try {
            conditionDetector.recordAwait(ready, "fixture-condition");
            ready.await(1, TimeUnit.MILLISECONDS);   // always timed
            conditionDetector.recordAwaitExit(ready, "fixture-condition", true);
            conditionDetector.recordSignal(ready, "fixture-condition", true);
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
        AsyncTestContext.simpleDateFormatDetector().recordFormat(SHARED_FORMAT, "shared-sdf");
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
        // A stateful operation inside a parallel stream races across the split, and a
        // non-thread-safe collector loses elements outright.
        var parallelDetector = AsyncTestContext.parallelStreamDetector();
        parallelDetector.recordParallelStream("fixture-stream");
        parallelDetector.recordStatefulOperation("fixture-stream", "sorted");
        parallelDetector.recordNonThreadSafeCollector("fixture-stream", "ArrayList");
        IntStream.range(0, 32).parallel().sum();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.RESOURCE_LEAKS})
    void resourceLeaks() {
        reachable("resourceLeakDetector()", AsyncTestContext::resourceLeakDetector);

        // A resource opened and never closed is the leak. The fixture closes its stream -
        // the leak is recorded against a resource whose close is deliberately never reported.
        var resourceDetector = AsyncTestContext.resourceLeakDetector();
        Object leaked = new Object();
        resourceDetector.registerResource(leaked, "leaked-resource", "InputStream");
        resourceDetector.recordResourceOpened(leaked, "leaked-resource");
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

    private static final Semaphore SHARED_PERMITS = new Semaphore(1);

    private static final List<Integer> SHARED_LIST =
        java.util.Collections.synchronizedList(new ArrayList<>(List.of(1, 2, 3)));

    private static final ReentrantLock LEAKED_LOCK = new ReentrantLock();

    private static final ArrayBlockingQueue<Integer> SHARED_QUEUE =
        new ArrayBlockingQueue<>(1);
}
