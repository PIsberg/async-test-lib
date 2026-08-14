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
        // A phaser party that never arrives leaves every other party waiting for a phase
        // that cannot advance; the timeout is what a consumer sees when that happens.
        var phaserDetector = AsyncTestContext.phaserDetector();
        Phaser phaser = new Phaser(1);
        phaserDetector.registerPhaser(phaser, "fixture-phaser", 1);
        phaserDetector.recordArrive(phaser);
        phaserDetector.recordTimeout(phaser);
        phaser.arriveAndDeregister();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STAMPED_LOCK})
    void stampedLock() {
        reachable("stampedLockDetector()", AsyncTestContext::stampedLockDetector);

        // An optimistic stamp that is never validated is not a read at all - the data behind
        // it may have been rewritten while it was being used.
        var stampedDetector = AsyncTestContext.stampedLockDetector();
        StampedLock lock = new StampedLock();
        stampedDetector.registerLock(lock, "fixture-stamped-lock");
        long stamp = lock.tryOptimisticRead();
        stampedDetector.recordOptimisticRead(lock, "fixture-stamped-lock", stamp);
        stampedDetector.recordOptimisticValidation(lock, "fixture-stamped-lock", stamp, false);
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
        // An Exchanger pairs threads two at a time; a partner that never arrives leaves this
        // one waiting until its timeout, which is the finding.
        var exchangerDetector = AsyncTestContext.exchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        exchangerDetector.registerExchanger(exchanger, "fixture-exchanger");
        try {
            exchangerDetector.recordExchangeStart(exchanger, "fixture-exchanger");
            exchanger.exchange("payload", 20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            exchangerDetector.recordTimeout(exchanger);
            // No partner arrived within the budget — the misuse being demonstrated.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SCHEDULED_EXECUTOR})
    void scheduledExecutor() {
        reachable("scheduledExecutorDetector()", AsyncTestContext::scheduledExecutorDetector);

        // A scheduled task that overruns its period delays every later run on the same
        // single-threaded scheduler, so the duration is what the detector measures.
        var schedulerDetector = AsyncTestContext.scheduledExecutorDetector();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulerDetector.registerExecutor(scheduler, "fixture-scheduler", 1);
        try {
            schedulerDetector.recordSchedule(scheduler, "fixture-scheduler", "slow-task");
            scheduler.schedule(() -> { spin(32); }, 1, TimeUnit.MILLISECONDS);
            schedulerDetector.recordTaskStart(scheduler, "fixture-scheduler", "slow-task");
            schedulerDetector.recordTaskComplete(scheduler, "fixture-scheduler", "slow-task",
                    30_000L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FORK_JOIN_POOL})
    void forkJoinPool() {
        reachable("forkJoinPoolDetector()", AsyncTestContext::forkJoinPoolDetector);

        // fork() without a matching join() abandons the task: nobody waits for it and any
        // exception it throws is never seen.
        var fjPoolDetector = AsyncTestContext.forkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);
        fjPoolDetector.registerPool(pool, "fixture-fj-pool", 2);
        try {
            fjPoolDetector.recordFork(pool, "fixture-fj-pool", "abandoned-task");
            fjPoolDetector.recordForkWithoutJoin("fixture-fj-pool", "abandoned-task");
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
        // A factory that leaves its threads unnamed, non-daemon and without an uncaught
        // exception handler is the default one - which is why the detector flags it. The
        // thread is recorded before it is tidied up below, because tidying it up is the fix.
        var factoryDetector = AsyncTestContext.threadFactoryDetector();
        ThreadFactory factory = Thread::new;
        factoryDetector.registerFactory(factory, "fixture-factory");
        Thread worker = factory.newThread(() -> spin(32));
        factoryDetector.recordThreadCreated(factory, "fixture-factory", worker);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
