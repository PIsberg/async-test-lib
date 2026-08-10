package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 21, virtual-thread-era executor and shared-generator group —
 * {@code VIRTUAL_THREAD_POOLING}, {@code PLATFORM_THREAD_PER_TASK} and
 * {@code SHARED_SPLITTABLE_RANDOM}.
 *
 * <p>These fixtures exist to prove each detector is reachable from the published surface and
 * records without throwing under real contention. They are not accuracy tests — that is
 * {@code DetectorAccuracyEvalTest}'s job inside the library — so nothing here asserts a finding.
 *
 * <p>The executor fixtures model rather than create the load their hazards imply: the pooling
 * fixture registers a lazily-initialized pool that never starts a worker, and the thread-per-task
 * fixture records threads it never starts. A fixture that actually spawned a thread storm would
 * be testing the CI host, not the recording surface.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} for the buggy-vs-fixed pair behind each one.
 */
class Phase21VirtualThreadExecutorAndGeneratorFixtureTest {

    /** Shared by both workers — recording the same instance from each is the point. */
    private static final SplittableRandom SHARED_GENERATOR = new SplittableRandom(42);

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VIRTUAL_THREAD_POOLING})
    void virtualThreadPooling() {
        reachable("virtualThreadPoolingDetector()",
                AsyncTestContext::virtualThreadPoolingDetector);

        // The hazard: a ThreadPoolExecutor whose factory manufactures virtual threads. The
        // factory probe creates one unstarted thread and discards it, and a fixed pool is lazy,
        // so registering starts nothing — shutdownNow() tears down an empty pool.
        var detector = AsyncTestContext.virtualThreadPoolingDetector();
        ExecutorService pooledVirtual = Executors.newFixedThreadPool(1, Thread.ofVirtual().factory());
        try {
            detector.registerExecutor(pooledVirtual, "fixturePooledVirtual");
            spin(8);
        } finally {
            pooledVirtual.shutdownNow();
        }
        // The record path must no-op cleanly for a platform caller like this worker.
        detector.recordTaskExecution("fixtureTask");
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.PLATFORM_THREAD_PER_TASK})
    void platformThreadPerTask() {
        reachable("platformThreadPerTaskDetector()",
                AsyncTestContext::platformThreadPerTaskDetector);

        // The hazard modelled, not created: the census records threads that are never started,
        // and two creations per run stay far below the churn threshold. Both the platform and
        // the virtual arm of recordThreadCreated are exercised.
        var detector = AsyncTestContext.platformThreadPerTaskDetector();
        detector.recordThreadCreated(new Thread(() -> { }, "fixture-per-task"));
        spin(8);
        detector.recordThreadCreated(Thread.ofVirtual().name("fixture-vt").unstarted(() -> { }));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_SPLITTABLE_RANDOM})
    void sharedSplittableRandom() {
        reachable("sharedSplittableRandomDetector()",
                AsyncTestContext::sharedSplittableRandomDetector);

        // The hazard: both workers record the same SplittableRandom instance. Recording the
        // sharing is the fixture's job; actually racing nextLong() on a shared instance is what
        // the catalog example demonstrates.
        var detector = AsyncTestContext.sharedSplittableRandomDetector();
        detector.registerGenerator(SHARED_GENERATOR, "fixtureGenerator");
        spin(8);
        detector.recordAccess(SHARED_GENERATOR, "fixtureGenerator", "nextLong");
    }
}
