package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FALSE_SHARING})
    void falseSharing() {
        reachable("falseSharingDetector()", AsyncTestContext::falseSharingDetector);

        // Two counters that would land on one cache line if they were fields of one object.
        AtomicLong left = new AtomicLong();
        AtomicLong right = new AtomicLong();
        for (int i = 0; i < 64; i++) {
            left.incrementAndGet();
            right.incrementAndGet();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WAKEUP_ISSUES})
    void wakeupIssues() {
        reachable("wakeupDetector()", AsyncTestContext::wakeupDetector);

        Object monitor = new Object();
        synchronized (monitor) {
            try {
                monitor.wait(1);          // always timed — never an unguarded wait()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            monitor.notifyAll();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONSTRUCTOR_SAFETY})
    void constructorSafety() {
        reachable("constructorSafetyValidator()", AsyncTestContext::constructorSafetyValidator);

        Config published = new Config(7);
        published.limit();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ABA_PROBLEM})
    void abaProblem() {
        reachable("abaProblemDetector()", AsyncTestContext::abaProblemDetector);

        // A -> B -> A on a plain atomic: the value looks unchanged to a naive CAS.
        AtomicLong slot = new AtomicLong(1);
        slot.compareAndSet(1, 2);
        slot.compareAndSet(2, 1);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_ORDER})
    void lockOrder() {
        reachable("lockOrderValidator()", AsyncTestContext::lockOrderValidator);

        ReentrantLock outer = new ReentrantLock();
        ReentrantLock inner = new ReentrantLock();
        outer.lock();
        try {
            inner.lock();
            inner.unlock();
        } finally {
            outer.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZERS})
    void synchronizers() {
        reachable("synchronizerMonitor()", AsyncTestContext::synchronizerMonitor);

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

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> { spin(32); });
        } finally {
            pool.shutdown();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MEMORY_ORDERING})
    void memoryOrdering() {
        reachable("memoryOrderingMonitor()", AsyncTestContext::memoryOrderingMonitor);

        // Store-store pair with no barrier between them.
        Pair pair = new Pair();
        pair.first = 1;
        pair.second = 2;
        spin(pair.first + pair.second);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ASYNC_PIPELINE})
    void asyncPipeline() {
        reachable("pipelineMonitor()", AsyncTestContext::pipelineMonitor);

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
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock(false);
        rw.readLock().lock();
        try {
            spin(32);
        } finally {
            rw.readLock().unlock();
        }
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
}
