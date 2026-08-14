package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.Exchanger;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.StampedLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 2, advanced-utility group — {@code PHASER} through {@code THREAD_FACTORY}.
 *
 * <p>Corresponding examples: {@code examples/64-phaser-misuse},
 * {@code examples/75-stamped-lock}, {@code examples/48-exchanger-misuse},
 * {@code examples/68-scheduled-executor}, {@code examples/50-fork-join-pool},
 * {@code examples/83-thread-factory}.
 */
class Phase02AdvancedUtilityDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "PhaserDetector",
                    "StampedLockDetector",
                    "ExchangerDetector",
                    "ScheduledExecutorDetector",
                    "ForkJoinPoolDetector",
                    "ThreadFactoryDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.PHASER})
    void phaser() {
        reachable("phaserDetector()", AsyncTestContext::phaserDetector);

        // One registered party per worker, arrived and deregistered in the same body so no
        // worker can be left waiting on a party that never arrives.
        Phaser phaser = new Phaser(1);
        phaser.arriveAndDeregister();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STAMPED_LOCK})
    void stampedLock() {
        reachable("stampedLockDetector()", AsyncTestContext::stampedLockDetector);

        StampedLock lock = new StampedLock();
        long stamp = lock.tryOptimisticRead();
        int value = spin(32);
        if (!lock.validate(stamp)) {          // the validation step misuse forgets
            stamp = lock.readLock();
            try {
                value = spin(32);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        spin(value % 8);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXCHANGER})
    void exchanger() {
        reachable("exchangerDetector()", AsyncTestContext::exchangerDetector);

        // exchange() with no partner blocks forever; the timed form is the safe usage and
        // the timeout branch is exactly what an Exchanger-misuse detector reports on.
        Exchanger<String> exchanger = new Exchanger<>();
        try {
            exchanger.exchange("payload", 20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            // No partner arrived within the budget — the misuse being demonstrated.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SCHEDULED_EXECUTOR})
    void scheduledExecutor() {
        reachable("scheduledExecutorDetector()", AsyncTestContext::scheduledExecutorDetector);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.schedule(() -> { spin(32); }, 1, TimeUnit.MILLISECONDS);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FORK_JOIN_POOL})
    void forkJoinPool() {
        reachable("forkJoinPoolDetector()", AsyncTestContext::forkJoinPoolDetector);

        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            pool.submit(() -> { spin(64); }).join();
        } finally {
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_FACTORY})
    void threadFactory() {
        reachable("threadFactoryDetector()", AsyncTestContext::threadFactoryDetector);

        // An unnamed, non-daemon factory — the hygiene problem the detector names.
        ThreadFactory factory = Thread::new;
        Thread worker = factory.newThread(() -> spin(32));
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
