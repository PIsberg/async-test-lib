package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 2, additional-concurrency group — {@code COUNTDOWN_LATCH} through
 * {@code LAZY_INIT_RACE}.
 *
 * <p>Corresponding examples: {@code examples/44-count-down-latch},
 * {@code examples/45-cyclic-barrier}, {@code examples/66-reentrant-lock},
 * {@code examples/93-volatile-array}, {@code examples/47-double-checked-locking},
 * {@code examples/94-wait-timeout}, {@code examples/05-lock-contention},
 * {@code examples/81-synchronized-non-final}, {@code examples/58-missed-signal},
 * {@code examples/55-lazy-init-race}.
 */
class Phase02AdditionalConcurrencyDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "CountDownLatchDetector",
                    "CyclicBarrierDetector",
                    "ReentrantLockDetector",
                    "VolatileArrayDetector",
                    "DoubleCheckedLockingDetector",
                    "WaitTimeoutDetector",
                    "LockContentionDetector",
                    "SynchronizedNonFinalDetector",
                    "MissedSignalDetector",
                    "LazyInitRaceDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COUNTDOWN_LATCH})
    void countDownLatch() {
        reachable("countDownLatchDetector()", AsyncTestContext::countDownLatchDetector);

        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        try {
            latch.await(100, TimeUnit.MILLISECONDS);   // always timed, never await()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CYCLIC_BARRIER})
    void cyclicBarrier() {
        reachable("cyclicBarrierDetector()", AsyncTestContext::cyclicBarrierDetector);

        // Parties = 1 so the barrier trips immediately regardless of worker scheduling.
        CyclicBarrier barrier = new CyclicBarrier(1);
        try {
            barrier.await(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException | TimeoutException e) {
            // Barrier misuse is the subject; a broken barrier must not fail the round.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.REENTRANT_LOCK})
    void reentrantLock() {
        reachable("reentrantLockDetector()", AsyncTestContext::reentrantLockDetector);

        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            lock.lock();          // reentrant acquisition, matched below
            try {
                spin(32);
            } finally {
                lock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VOLATILE_ARRAY})
    void volatileArray() {
        reachable("volatileArrayDetector()", AsyncTestContext::volatileArrayDetector);

        // volatile on the reference says nothing about the elements — the trap.
        VOLATILE_REF[0] = VOLATILE_REF[0] + 1;
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DOUBLE_CHECKED_LOCKING})
    void doubleCheckedLocking() {
        reachable("doubleCheckedLockingDetector()", AsyncTestContext::doubleCheckedLockingDetector);

        Holder.instance();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WAIT_TIMEOUT})
    void waitTimeout() {
        reachable("waitTimeoutDetector()", AsyncTestContext::waitTimeoutDetector);

        Object monitor = new Object();
        synchronized (monitor) {
            try {
                monitor.wait(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_CONTENTION})
    void lockContention() {
        reachable("lockContentionDetector()", AsyncTestContext::lockContentionDetector);

        // One static lock, both workers: the contention the detector measures.
        CONTENDED.lock();
        try {
            spin(256);
        } finally {
            CONTENDED.unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_NON_FINAL})
    void synchronizedNonFinal() {
        reachable("synchronizedNonFinalDetector()", AsyncTestContext::synchronizedNonFinalDetector);

        MutableMonitor holder = new MutableMonitor();
        holder.guardedWork();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MISSED_SIGNAL})
    void missedSignal() {
        reachable("missedSignalDetector()", AsyncTestContext::missedSignalDetector);

        // notify before anyone waits: the signal that goes nowhere.
        Object monitor = new Object();
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAZY_INIT_RACE})
    void lazyInitRace() {
        reachable("lazyInitRaceDetector()", AsyncTestContext::lazyInitRaceDetector);

        // Unsynchronised lazy init: both workers can build their own instance.
        if (LAZY.get() == null) {
            LAZY.compareAndSet(null, "built");
        }
    }

    /** volatile reference, non-volatile elements. */
    private static volatile int[] VOLATILE_REF = new int[] {0};

    private static final ReentrantLock CONTENDED = new ReentrantLock();

    private static final AtomicReference<String> LAZY = new AtomicReference<>();

    /** Textbook double-checked locking without a volatile field. */
    private static final class Holder {
        private static Holder instance;

        static Holder instance() {
            if (instance == null) {
                synchronized (Holder.class) {
                    if (instance == null) {
                        instance = new Holder();
                    }
                }
            }
            return instance;
        }
    }

    /** Synchronising on a field that can be reassigned. */
    private static final class MutableMonitor {
        private Object monitor = new Object();

        void guardedWork() {
            synchronized (monitor) {
                spin(32);
            }
        }
    }
}
