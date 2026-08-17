package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 23, virtual-thread scale group — {@code VIRTUAL_THREAD_RESOURCE_SATURATION},
 * {@code VIRTUAL_THREAD_MONITOR_SERIALIZATION} and {@code THREAD_LOCAL_CACHE_DEGRADATION}.
 *
 * <p>Each fixture proves its detector is reachable from the published artifact, runs the hazard
 * through the detector's public recording API, and asserts in {@code @AfterAll} that the finding
 * came back out through {@link AsyncFindings}.
 *
 * <p>All three detectors are about what happens at <em>scale</em>, and all three require the
 * threads doing the work to be virtual — a platform worker produces no finding by design. The
 * {@code @AsyncTest} workers are platform threads, so each fixture spawns its own small fan-out of
 * virtual threads inside the round and holds them at a latch, which makes the peak deterministic
 * instead of a matter of scheduling luck. Six virtual threads is enough to clear every threshold;
 * the ten thousand that make this hurt in production would be testing the CI host.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} for the buggy-vs-fixed pair behind each one.
 */
class Phase23VirtualThreadScaleDetectorsFixtureTest {

    private static final int FAN_OUT = 6;

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "VirtualThreadResourceSaturationDetector",
                    "VirtualThreadMonitorSerializationDetector",
                    "ThreadLocalCacheDegradationDetector");
        } finally {
            findings.close();
        }
    }

    /** The per-thread helper whose instance count is the third fixture's finding. */
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT));

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION})
    void virtualThreadResourceSaturation() {
        reachable("vthreadResourceSaturationDetector()",
                AsyncTestContext::vthreadResourceSaturationDetector);

        // The hazard: an unbounded virtual fan-out onto a resource that serves two at a time.
        // Nothing here bounds the arrivals, so all six queue at once.
        var detector = AsyncTestContext.vthreadResourceSaturationDetector();
        String resource = "connections-" + Thread.currentThread().getName();
        detector.registerResource(resource, 2);

        fanOut(gate -> {
            detector.recordAcquireStart(resource, Thread.currentThread());
            gate.run();
            detector.recordAcquired(resource, Thread.currentThread());
        });
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION})
    void virtualThreadMonitorSerialization() {
        reachable("vthreadMonitorSerializationDetector()",
                AsyncTestContext::vthreadMonitorSerializationDetector);

        // The hazard: every virtual thread funnels through one monitor. Since JDK 24 this no
        // longer pins a carrier, so the pinning detector stays quiet and only the queue is left.
        var detector = AsyncTestContext.vthreadMonitorSerializationDetector();
        Object lock = new Object();

        fanOut(gate -> {
            detector.recordMonitorEnter(lock, "fixtureSessionCache", Thread.currentThread());
            gate.run();
            synchronized (lock) {
                detector.recordMonitorAcquired(lock, Thread.currentThread());
                spin(8);
            }
        });
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LOCAL_CACHE_DEGRADATION})
    void threadLocalCacheDegradation() {
        reachable("threadLocalCacheDegradationDetector()",
                AsyncTestContext::threadLocalCacheDegradationDetector);

        // The hazard: a ThreadLocal formatter, which on a pool would be one instance per worker
        // and here is one per task. No latch needed - the instance count is what matters, not
        // when the threads overlap.
        var detector = AsyncTestContext.threadLocalCacheDegradationDetector();
        fanOut(gate -> {
            gate.run();
            detector.recordCachedValue("fixtureFormat", FORMAT.get(), Thread.currentThread());
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The body of one virtual worker; {@code gate.run()} holds it until the fan-out is complete. */
    private interface Worker {
        void run(Runnable gate);
    }

    /**
     * Runs {@link #FAN_OUT} virtual threads, holding each at the gate until every one has arrived.
     *
     * <p>The gate is what makes the peak deterministic: without it the first worker can finish
     * before the last one starts, and a detector that measures simultaneity would see a queue of
     * one and report nothing on a fast machine.
     */
    private static void fanOut(Worker worker) {
        CountDownLatch allArrived = new CountDownLatch(FAN_OUT);
        CountDownLatch release = new CountDownLatch(1);
        Runnable gate = () -> {
            allArrived.countDown();
            await(release);
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < FAN_OUT; i++) {
            threads.add(Thread.ofVirtual().start(() -> worker.run(gate)));
        }
        await(allArrived);
        release.countDown();
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fan-out never assembled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
