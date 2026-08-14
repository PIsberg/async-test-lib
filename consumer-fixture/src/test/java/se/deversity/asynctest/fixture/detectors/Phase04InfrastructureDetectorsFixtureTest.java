package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 4, infrastructure &amp; resource-management group — {@code THREAD_LEAKS} through
 * {@code THREAD_STARVATION}.
 *
 * <p>Corresponding examples: {@code examples/84-thread-leak},
 * {@code examples/74-sleep-in-lock}, {@code examples/89-unbounded-queue},
 * {@code examples/87-thread-starvation}.
 */
class Phase04InfrastructureDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "ThreadLeakDetector",
                    "SleepInLockDetector",
                    "UnboundedQueueDetector",
                    "ThreadStarvationDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LEAKS})
    void threadLeaks() {
        reachable("threadLeakDetector()", AsyncTestContext::threadLeakDetector);

        // An executor created inside a test body is the leak shape; this one is shut down
        // and joined so the fixture itself leaves no threads behind.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> { spin(32); });
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SLEEP_IN_LOCK})
    void sleepInLock() {
        reachable("sleepInLockDetector()", AsyncTestContext::sleepInLockDetector);

        // Holding a lock across a sleep — short enough not to dominate the round.
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            pause(1);
        } finally {
            lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.UNBOUNDED_QUEUE})
    void unboundedQueue() {
        reachable("unboundedQueueDetector()", AsyncTestContext::unboundedQueueDetector);

        // LinkedBlockingQueue with no capacity argument: unbounded, the memory hazard.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            pool.execute(() -> spin(32));
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_STARVATION})
    void threadStarvation() {
        reachable("threadStarvationDetector()", AsyncTestContext::threadStarvationDetector);

        // More submitted work than the pool can run at once: the starvation shape.
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            for (int i = 0; i < 4; i++) {
                pool.execute(() -> spin(64));
            }
        } finally {
            pool.shutdown();
        }
    }
}
