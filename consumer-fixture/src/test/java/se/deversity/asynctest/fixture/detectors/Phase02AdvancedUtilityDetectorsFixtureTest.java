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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /** The callers {@link #exchanger()} leaves parked, released once the findings are read. */
    private static final Queue<Thread> ORPHANED_EXCHANGERS = new ConcurrentLinkedQueue<>();

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
            ORPHANED_EXCHANGERS.forEach(Thread::interrupt);
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.PHASER})
    void phaser() {
        reachable("phaserDetector()", AsyncTestContext::phaserDetector);

        // A phaser created for one party, left twice. The first arriveAndDeregister takes the
        // party count to zero, which terminates the phaser; the second returns a negative phase
        // instead of coordinating with anyone. That short count is the finding (#587). Each
        // worker uses its own phaser and nothing blocks, so no worker can be left waiting.
        var phaserDetector = AsyncTestContext.phaserDetector();
        Phaser phaser = new Phaser(1);
        phaserDetector.registerPhaser(phaser, "fixture-phaser", 1);
        phaserDetector.recordArrival(phaser, phaser.arriveAndDeregister());
        phaserDetector.recordArrival(phaser, phaser.arriveAndDeregister());
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
    void exchanger() throws InterruptedException {
        reachable("exchangerDetector()", AsyncTestContext::exchangerDetector);

        // An Exchanger pairs threads two at a time, and an untimed exchange() with no partner
        // blocks forever. That orphan is the finding. A timed exchange that handles its
        // TimeoutException is the fix, and is deliberately not reported (#585), so the fixture
        // leaves a real caller parked in exchange() rather than recording a timeout.
        var exchangerDetector = AsyncTestContext.exchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        exchangerDetector.registerExchanger(exchanger, "fixture-exchanger");
        exchangerDetector.recordExchangeStart(exchanger, "fixture-exchanger");
        Thread orphan = new Thread(() -> {
            try {
                String received = exchanger.exchange("payload"); // nobody else is on this exchanger
                exchangerDetector.recordExchangeComplete(exchanger, "fixture-exchanger", received);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // the @AfterAll cleanup, after analysis
            }
        }, "fixture-exchanger-orphan");
        orphan.setDaemon(true);
        ORPHANED_EXCHANGERS.add(orphan);
        orphan.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (orphan.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
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
