package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.registerOnce;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 2, core group — {@code FALSE_SHARING} through {@code READ_WRITE_LOCK_FAIRNESS}.
 *
 * <p>Corresponding examples: {@code examples/30-false-sharing},
 * {@code examples/95-wakeup-issues}, {@code examples/100-constructor-safety},
 * {@code examples/29-aba-problem}, {@code examples/31-lock-order-violation},
 * {@code examples/102-synchronizer-monitor}, {@code examples/103-thread-pool-monitor},
 * {@code examples/101-memory-ordering}, {@code examples/98-async-pipeline-monitor},
 * {@code examples/32-rwlock-starvation}.
 */
class Phase02CoreDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "WakeupDetector",
                    "ConstructorSafetyValidator",
                    "ABAProblemDetector",
                    "LockOrderValidator",
                    "SynchronizerMonitor",
                    "ThreadPoolMonitor",
                    "MemoryOrderingMonitor",
                    "PipelineMonitor",
                    "ReadWriteLockMonitor");
            // FalseSharingDetector is gated behind -Dasync-test.experimental.false-sharing=true
            // and analyze() returns nothing without it. The fixture below still records the
            // adjacent-field access so the recording path stays exercised from a consumer, but
            // asserting a finding would assert the gate is off, which it is not by default.
            assertNoneReported(findings, "FalseSharingDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FALSE_SHARING})
    void falseSharing() {
        reachable("falseSharingDetector()", AsyncTestContext::falseSharingDetector);

        // Two counters that would land on one cache line if they were fields of one object.
        // Two hot fields on one object share a cache line, so each worker's write
        // invalidates the other's line even though they never touch the same field.
        var falseSharing = AsyncTestContext.falseSharingDetector();
        for (int i = 0; i < 64; i++) {
            falseSharing.recordFieldAccess(HOT_PAIR, "first", int.class);
            HOT_PAIR.first++;
            falseSharing.recordFieldAccess(HOT_PAIR, "second", int.class);
            HOT_PAIR.second++;
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WAKEUP_ISSUES})
    void wakeupIssues() {
        reachable("wakeupDetector()", AsyncTestContext::wakeupDetector);

        // A notify with nobody waiting is simply thrown away: the waiter that arrives next
        // waits for a signal that has already happened.
        var wakeup = AsyncTestContext.wakeupDetector();
        Object monitor = new Object();
        synchronized (monitor) {
            try {
                monitor.wait(1);          // always timed — never an unguarded wait()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            wakeup.recordNotify(monitor, true);
            monitor.notifyAll();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONSTRUCTOR_SAFETY})
    void constructorSafety() {
        reachable("constructorSafetyValidator()", AsyncTestContext::constructorSafetyValidator);

        // A field read while the constructor is still running can see the default value
        // rather than the assigned one - the object is published before it is finished.
        // The finding is a field read by a DIFFERENT thread while construction is still in
        // progress - one thread doing start, access and end to itself is the safe case and
        // reports nothing however it is written.
        //
        // The workers therefore split: one starts building the shared object, the other reads
        // a field before the build is recorded as finished. A latch orders the two rather than
        // a sleep, so the overlap is guaranteed instead of likely.
        var constructorSafety = AsyncTestContext.constructorSafetyValidator();
        if (CONSTRUCTION_ROLE.getAndIncrement() % 2 == 0) {
            constructorSafety.recordConstructionStart(SHARED_CONFIG);
            CONSTRUCTION_STARTED.countDown();
            spin(64);
            // No recordConstructionEnd until the reader has been: the object is visible to
            // another thread before it is finished, which is the whole hazard.
        } else {
            try {
                CONSTRUCTION_STARTED.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            constructorSafety.recordFieldAccess(SHARED_CONFIG, "limit", System.nanoTime());
            SHARED_CONFIG.limit();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ABA_PROBLEM})
    void abaProblem() {
        reachable("abaProblemDetector()", AsyncTestContext::abaProblemDetector);

        // A -> B -> A on a plain atomic: the value looks unchanged to a naive CAS.
        // A to B and back to A: a CAS that only compares values cannot tell that the world
        // changed underneath it, because the value it compares is the one it expected.
        //
        // One history per worker, not one shared history: the detector scans a variable's
        // change list for the shape A, B, A before the CAS, and two workers appending the same
        // pair into one list can interleave as 1->2, 1->2, 2->1, 2->1, which contains no such
        // shape. That interleaving is what made this fixture report nothing on one JUnit leg
        // in seven (2026-08-15) while passing on the rest. A per-worker slot keeps each
        // history the sequence the fixture means to show, on every schedule.
        var aba = AsyncTestContext.abaProblemDetector();
        String slotName = "aba-slot-" + Thread.currentThread().getName();
        AtomicLong slot = new AtomicLong(1);
        aba.recordValueChange(slotName, 1L, 2L);
        slot.compareAndSet(1, 2);
        aba.recordValueChange(slotName, 2L, 1L);
        slot.compareAndSet(2, 1);
        aba.recordCASAttempt(slotName, 1L, 3L, true, 1L);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_ORDER})
    void lockOrder() {
        reachable("lockOrderValidator()", AsyncTestContext::lockOrderValidator);

        // The cycle needs both orderings, so the workers take opposite roles rather than
        // racing for them: A then B on one, B then A on the other. Recorded rather than
        // actually nested in both directions, because a fixture that really took the locks
        // in opposite orders would deadlock the consumer's build.
        var lockOrder = AsyncTestContext.lockOrderValidator();
        ReentrantLock first = ORDER_ROLE.getAndIncrement() % 2 == 0 ? OUTER_LOCK : INNER_LOCK;
        ReentrantLock second = first == OUTER_LOCK ? INNER_LOCK : OUTER_LOCK;
        lockOrder.recordLockAcquisition(first);
        lockOrder.recordLockAcquisition(second);
        lockOrder.recordLockRelease(second);
        lockOrder.recordLockRelease(first);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZERS})
    void synchronizers() {
        reachable("synchronizerMonitor()", AsyncTestContext::synchronizerMonitor);

        // A barrier that expects more parties than ever arrive never trips, and everyone
        // already waiting on it waits forever.
        var synchronizers = AsyncTestContext.synchronizerMonitor();
        registerOnce("synchronizer",
                () -> synchronizers.registerSynchronizer(SHARED_BARRIER_TOKEN, 4));
        synchronizers.recordBarrierArrival(SHARED_BARRIER_TOKEN);
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            spin(32);
        } finally {
            lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_POOL})
    void threadPool() {
        reachable("threadPoolMonitor()", AsyncTestContext::threadPoolMonitor);

        // A pool that rejects work has run out of both threads and queue: the caller's task
        // is simply dropped, which is what the rejection record represents.
        var poolMonitor = AsyncTestContext.threadPoolMonitor();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        poolMonitor.registerPool(pool, "fixture-pool", 2, 2, 1);
        try {
            poolMonitor.recordTaskSubmitted(pool);
            pool.submit(() -> { spin(32); });
            poolMonitor.recordTaskRejected(pool, "queue full");
        } finally {
            pool.shutdown();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MEMORY_ORDERING})
    void memoryOrdering() {
        reachable("memoryOrderingMonitor()", AsyncTestContext::memoryOrderingMonitor);

        // Store-store pair with no barrier between them.
        // Two unsynchronised writes can be seen in either order by another thread, so a
        // reader can observe the second write without the first.
        // The monitor pairs a WRITE with the READ that immediately follows it on the same
        // location FROM ANOTHER THREAD. A worker that writes and then reads the same location
        // itself only ever produces same-thread pairs, which are skipped - so the workers take
        // opposite roles: one writes, the other reads the stale value.
        // The monitor pairs a WRITE with the READ that IMMEDIATELY FOLLOWS it on the same
        // location from another thread, so the two have to arrive in that order. Splitting the
        // roles is not enough on its own: when the reader won the race the log held
        // (read, write), which is not a write-then-read pair and reports nothing. That is why
        // this passed on JDK 26 locally and failed on Java 21 in CI.
        //
        // A latch orders the reader behind the writer, so the pair exists on every schedule.
        var ordering = AsyncTestContext.memoryOrderingMonitor();
        Pair pair = SHARED_PAIR;
        if (ORDERING_ROLE.getAndIncrement() % 2 == 0) {
            pair.first = 1;
            ordering.recordWrite("Pair.first", 1);
            WRITE_RECORDED.countDown();
        } else {
            try {
                WRITE_RECORDED.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ordering.recordRead("Pair.first", 0);    // the stale value the reordering allows
        }
        spin(pair.first + pair.second);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ASYNC_PIPELINE})
    void asyncPipeline() {
        reachable("pipelineMonitor()", AsyncTestContext::pipelineMonitor);

        // An event published into a stage that never processes it is stuck: the pipeline
        // looks healthy from the producer's side and nothing downstream ever runs.
        var pipeline = AsyncTestContext.pipelineMonitor();
        registerOnce("pipeline-stage", () -> pipeline.registerStage("fixture-stage"));
        pipeline.recordEventPublished("fixture-stage", "event-1");
        pipeline.recordEventFailed("fixture-stage", "event-1", "no consumer");
        CompletableFuture.completedFuture(1)
            .thenApply(v -> v + 1)
            .thenApply(Object::toString)
            .join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.READ_WRITE_LOCK_FAIRNESS})
    void readWriteLockFairness() {
        reachable("readWriteLockMonitor()", AsyncTestContext::readWriteLockMonitor);

        // Non-fair by construction: the fairness monitor's subject.
        // A non-fair read-write lock lets a steady stream of readers starve the writer
        // indefinitely, which is what the reader-to-writer ratio below represents.
        var rwMonitor = AsyncTestContext.readWriteLockMonitor();
        ReentrantReadWriteLock rw = SHARED_RW;
        registerOnce("rw-lock", () -> rwMonitor.registerLock(rw, "fixture-rw-lock"));
        for (int i = 0; i < 32; i++) {
            rw.readLock().lock();
            rwMonitor.recordReadLockAcquired(rw, 0L);
            try {
                spin(8);
            } finally {
                rw.readLock().unlock();
                rwMonitor.recordReadLockReleased(rw);
            }
        }
        rwMonitor.recordWriteLockAcquired(rw, 5_000L);   // the writer that waited behind them
    }

    /** A value published from a constructor without a safe-publication barrier. */
    private static final class Config {
        private int limit;

        Config(int limit) {
            this.limit = limit;
        }

        int limit() {
            return limit;
        }
    }

    private static final class Pair {
        int first;
        int second;
    }

    private static final Pair HOT_PAIR = new Pair();

    private static final Pair SHARED_PAIR = new Pair();

    private static final ReentrantLock OUTER_LOCK = new ReentrantLock();

    private static final ReentrantLock INNER_LOCK = new ReentrantLock();

    /** Assigns the two halves of the lock-order cycle so both are always recorded. */
    private static final java.util.concurrent.atomic.AtomicInteger ORDER_ROLE =
        new java.util.concurrent.atomic.AtomicInteger();

    private static final Object SHARED_BARRIER_TOKEN = new Object();

    private static final ReentrantReadWriteLock SHARED_RW = new ReentrantReadWriteLock(false);

    /** Splits the writer and reader roles so the cross-thread pair always exists. */
    private static final java.util.concurrent.atomic.AtomicInteger ORDERING_ROLE =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Orders the reader behind the writer, so the write-then-read pair always exists. */
    private static final java.util.concurrent.CountDownLatch WRITE_RECORDED =
        new java.util.concurrent.CountDownLatch(1);

    private static final Config SHARED_CONFIG = new Config(7);

    /** Splits the builder and reader roles for the constructor-safety fixture. */
    private static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTION_ROLE =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Orders the reader after the builder, so the overlap is guaranteed. */
    private static final java.util.concurrent.CountDownLatch CONSTRUCTION_STARTED =
        new java.util.concurrent.CountDownLatch(1);
}
