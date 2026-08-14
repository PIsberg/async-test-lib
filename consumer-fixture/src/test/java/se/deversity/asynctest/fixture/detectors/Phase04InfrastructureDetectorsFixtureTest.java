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
        // The detector reports a thread it was told about that is still alive and was never
        // recorded as ended. A pool worker that finishes and a pool that shuts down cleanly is
        // the fix, not the hazard - which is why this fixture reported nothing before.
        //
        // The thread below is genuinely still running when the round is analysed and exits on
        // its own shortly after, so the hazard is real without being left behind.
        var leakDetector = AsyncTestContext.threadLeakDetector();
        Thread leaked = new Thread(() -> pause(3_000), "fixture-leaked-thread");
        leaked.setDaemon(true);
        leaked.start();
        leakDetector.recordThreadStart(leaked, "fixture-leaked-thread");
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
               useVirtualThreads = false, includes = {DetectorType.SLEEP_IN_LOCK})
    void sleepInLock() {
        reachable("sleepInLockDetector()", AsyncTestContext::sleepInLockDetector);

        // Holding a lock across a sleep — short enough not to dominate the round.
        // recordSleep only reports when the calling thread actually holds a lock at the time,
        // which it establishes by inspecting the thread rather than taking the caller's word.
        // A ReentrantLock is not a monitor, so the sleep has to happen inside a synchronized
        // block for the detector to see the lock it is complaining about.
        // useVirtualThreads = false above is load-bearing, not tidying. recordSleep decides
        // whether a lock is held by asking ThreadMXBean, which returns nothing for a virtual
        // thread - so on the library's DEFAULT virtual-thread workers this detector cannot fire
        // at all. SleepInLockDetectorTest pins that blind spot; this fixture proves the
        // detector still works on the platform threads where it can see the monitor.
        var sleepDetector = AsyncTestContext.sleepInLockDetector();
        sleepDetector.startMonitoring();
        synchronized (SLEEP_MONITOR) {
            sleepDetector.recordSleep(50L);
            pause(1);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.UNBOUNDED_QUEUE})
    void unboundedQueue() {
        reachable("unboundedQueueDetector()", AsyncTestContext::unboundedQueueDetector);

        // LinkedBlockingQueue with no capacity argument: unbounded, the memory hazard.
        // An unbounded LinkedBlockingQueue never applies back-pressure: the pool accepts work
        // faster than it can run it until the heap gives out. Capacity -1 is how the detector
        // is told the queue has no bound.
        var queueDetector = AsyncTestContext.unboundedQueueDetector();
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, queue);
        queueDetector.recordQueueCreation(queue, "unbounded-work-queue", -1);
        try {
            for (int i = 0; i < 8; i++) {
                pool.execute(() -> spin(32));
                queueDetector.recordEnqueue(queue);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_STARVATION})
    void threadStarvation() {
        reachable("threadStarvationDetector()", AsyncTestContext::threadStarvationDetector);

        // More submitted work than the pool can run at once: the starvation shape.
        // One thread, four tasks: the queue wait grows with every task, which is starvation.
        // The submit timestamp is what lets the detector measure the wait.
        var starvationDetector = AsyncTestContext.threadStarvationDetector();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        starvationDetector.registerExecutor(pool, "starved-pool", 1);
        try {
            for (int i = 0; i < 4; i++) {
                long submittedAt = System.nanoTime() - TimeUnit.SECONDS.toNanos(2);
                pool.execute(() -> spin(64));
                starvationDetector.recordTaskStart("starved-pool", submittedAt);
                starvationDetector.recordTaskEnd("starved-pool");
            }
        } finally {
            pool.shutdown();
        }
    }

    /** Held while sleeping - the monitor the detector reports. */
    private static final Object SLEEP_MONITOR = new Object();
}
