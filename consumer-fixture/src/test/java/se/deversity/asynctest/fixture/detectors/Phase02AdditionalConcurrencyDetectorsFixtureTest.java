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

        // A latch awaited with a timeout that expires means the work it guards never
        // finished, and the waiter carried on as though it had.
        var latchDetector = AsyncTestContext.countDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(2);      // one more than anyone counts down
        latchDetector.registerLatch(latch, "fixture-latch", 2);
        latch.countDown();
        latchDetector.recordCountDown(latch);
        try {
            latchDetector.recordTimeout(latch);
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
        // A barrier one party short never trips, and once one waiter times out the barrier
        // is broken for everybody else too.
        var barrierDetector = AsyncTestContext.cyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(1);
        barrierDetector.registerBarrier(barrier, "fixture-barrier", 1);
        try {
            barrierDetector.recordArrival(barrier);
            barrierDetector.recordTimeout(barrier);
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

        // A lock acquisition that times out means another worker held it too long; the
        // detector reports the timeout rather than the reentrancy, which is legal.
        var reentrantDetector = AsyncTestContext.reentrantLockDetector();
        ReentrantLock lock = SHARED_REENTRANT;
        reentrantDetector.registerLock(lock, "shared-reentrant-lock");
        lock.lock();
        reentrantDetector.recordLockAcquired(lock, Thread.currentThread().getName());
        reentrantDetector.recordLockTimeout(lock);
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
        // volatile on an array reference publishes the reference, not the elements: every
        // element read and write is as unsynchronised as it would be without the keyword.
        var arrayDetector = AsyncTestContext.volatileArrayDetector();
        arrayDetector.registerArray(VOLATILE_REF, "volatile-ref", int.class);
        arrayDetector.recordElementRead(VOLATILE_REF, 0, "volatile-ref");
        int next = VOLATILE_REF[0] + 1;
        VOLATILE_REF[0] = next;
        arrayDetector.recordElementWrite(VOLATILE_REF, 0, "volatile-ref");
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DOUBLE_CHECKED_LOCKING})
    void doubleCheckedLocking() {
        reachable("doubleCheckedLockingDetector()", AsyncTestContext::doubleCheckedLockingDetector);

        // Double-checked locking without a volatile field: the second thread can see a
        // non-null reference to an object whose constructor has not finished publishing.
        var dclDetector = AsyncTestContext.doubleCheckedLockingDetector();
        dclDetector.registerDCL("Holder.instance", false, true, true, true);
        dclDetector.recordAccess("Holder.instance", true, false);
        Holder.instance();
        dclDetector.recordAccess("Holder.instance", false, true);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WAIT_TIMEOUT})
    void waitTimeout() {
        reachable("waitTimeoutDetector()", AsyncTestContext::waitTimeoutDetector);

        // wait() with no timeout never returns if the notify has already happened, so the
        // detector reports untimed waits and the notifications that should have matched them.
        var waitDetector = AsyncTestContext.waitTimeoutDetector();
        Object monitor = new Object();
        synchronized (monitor) {
            waitDetector.recordInfiniteWait(monitor, "fixture-monitor",
                    Thread.currentThread().getName());
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
        // Two workers on one lock: whoever loses waits, and the detector counts how often
        // an acquisition had to wait rather than how long the section took.
        var contentionDetector = AsyncTestContext.lockContentionDetector();
        contentionDetector.recordAcquireAttempt(CONTENDED, "contended-lock");
        contentionDetector.recordContention(CONTENDED, "contended-lock");
        CONTENDED.lock();
        contentionDetector.recordAcquired(CONTENDED, "contended-lock");
        try {
            spin(256);
        } finally {
            CONTENDED.unlock();
            contentionDetector.recordReleased(CONTENDED, "contended-lock");
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYNCHRONIZED_NON_FINAL})
    void synchronizedNonFinal() {
        reachable("synchronizedNonFinalDetector()", AsyncTestContext::synchronizedNonFinalDetector);

        // Synchronizing on a non-final field means the monitor can be replaced underneath
        // the threads using it, and two workers can then be inside the same section at once.
        // The detector reports a monitor slot whose reference CHANGED - one identity is just
        // a lock. So the fixture does the thing the hazard is named for: it reassigns the
        // non-final monitor field while workers are synchronizing on it, which is how two
        // threads end up inside the same guarded section at once.
        var nonFinalDetector = AsyncTestContext.synchronizedNonFinalDetector();
        MutableMonitor holder = SHARED_MUTABLE_MONITOR;
        nonFinalDetector.recordLockObject(holder.monitor(), "monitor", MutableMonitor.class);
        holder.guardedWork();
        holder.replaceMonitor();
        nonFinalDetector.recordLockObject(holder.monitor(), "monitor", MutableMonitor.class);
        holder.guardedWork();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MISSED_SIGNAL})
    void missedSignal() {
        reachable("missedSignalDetector()", AsyncTestContext::missedSignalDetector);

        // notify before anyone waits: the signal that goes nowhere.
        // A notify that arrives before anyone is waiting is simply lost: the waiter that
        // turns up afterwards waits for a signal that has already been and gone.
        var signalDetector = AsyncTestContext.missedSignalDetector();
        Object monitor = new Object();
        synchronized (monitor) {
            signalDetector.recordNotify("fixture-condition");
            monitor.notifyAll();
        }
        signalDetector.recordWait("fixture-condition");
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAZY_INIT_RACE})
    void lazyInitRace() {
        reachable("lazyInitRaceDetector()", AsyncTestContext::lazyInitRaceDetector);

        // Unsynchronised lazy init: both workers can build their own instance.
        // A non-volatile lazy field checked for null and then assigned: both workers can see
        // null and both can build, and a reader can see the reference before the object.
        // The race is BOTH workers seeing null and both deciding to build. Reading the live
        // value here instead makes that depend on the scheduler: whichever worker loses sees
        // the winner's value and records no null at all, so the fixture reported a finding
        // when run alone and none when run after the rest of the suite.
        //
        // PRE_ROUND_LAZY_WAS_NULL is the field's state captured before the round starts, so
        // both workers record the null they would each have observed. A genuine simultaneous
        // double-null is precisely the interleaving that cannot be forced, which is why it is
        // arranged rather than raced.
        var lazyDetector = AsyncTestContext.lazyInitRaceDetector();
        lazyDetector.recordNullCheck("Phase02.LAZY", PRE_ROUND_LAZY_WAS_NULL, false);
        if (LAZY.get() == null) {
            LAZY.compareAndSet(null, "built");
        }
        lazyDetector.recordInitialization("Phase02.LAZY");
    }

    /** volatile reference, non-volatile elements. */
    private static volatile int[] VOLATILE_REF = new int[] {0};

    private static final ReentrantLock CONTENDED = new ReentrantLock();

    private static final ReentrantLock SHARED_REENTRANT = new ReentrantLock();

    private static final MutableMonitor SHARED_MUTABLE_MONITOR = new MutableMonitor();

    private static final AtomicReference<String> LAZY = new AtomicReference<>();

    /** The field's state before the round: the null both workers would have seen. */
    private static final boolean PRE_ROUND_LAZY_WAS_NULL = LAZY.get() == null;

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

        Object monitor() {
            return monitor;
        }

        /** Reassigns the guard - the whole reason a monitor field should be final. */
        void replaceMonitor() {
            monitor = new Object();
        }

        void guardedWork() {
            synchronized (monitor) {
                spin(32);
            }
        }
    }
}
