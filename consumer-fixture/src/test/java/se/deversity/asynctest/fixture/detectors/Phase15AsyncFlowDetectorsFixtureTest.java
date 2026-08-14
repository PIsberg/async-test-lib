package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 15, async-flow &amp; lock-usage group — {@code COMPLETABLE_FUTURE_OBTRUDE_ABUSE}
 * through {@code COMPLETABLE_FUTURE_BLOCKING_CALLBACK}.
 *
 * <p>Corresponding examples: {@code examples/109-completablefuture-obtrude-abuse},
 * {@code examples/110-spurious-wakeup-hazard}, {@code examples/111-lock-upgrade-deadlock},
 * {@code examples/112-try-lock-misuse},
 * {@code examples/113-completablefuture-blocking-callback}.
 */
class Phase15AsyncFlowDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "CompletableFutureObtrudeDetector",
                    "SpuriousWakeupDetector",
                    "LockUpgradeDeadlockDetector",
                    "TryLockMisuseDetector",
                    "CompletableFutureBlockingCallbackDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE})
    void completableFutureObtrudeAbuse() {
        reachable("completableFutureObtrudeDetector()",
            AsyncTestContext::completableFutureObtrudeDetector);

        // obtrudeValue overwrites an already-published result, so downstream stages can
        // observe two different values for one future.
        // obtrudeValue rewrites a result other threads may already have read. It is the one
        // CompletableFuture API with no safe concurrent use, which is why it has a detector.
        CompletableFuture<String> future = CompletableFuture.completedFuture("first");
        AsyncTestContext.completableFutureObtrudeDetector()
                .recordObtrude(future, "fixture-future", Thread.currentThread());
        future.obtrudeValue("second");
        future.join();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SPURIOUS_WAKEUP_HAZARD})
    void spuriousWakeupHazard() {
        reachable("spuriousWakeupHazardDetector()",
            AsyncTestContext::spuriousWakeupHazardDetector);

        // wait() must be inside a loop re-checking the condition: a wakeup is not a
        // guarantee that the condition holds.
        // wait() can return without any notify at all, so a wait that is not wrapped in a
        // loop re-checking its condition is a latent bug. The fixture loops - insideLoop=false
        // below records the hazard shape the detector reports, which is the one a consumer
        // writes by accident.
        Object monitor = new Object();
        boolean[] ready = {false};
        synchronized (monitor) {
            AsyncTestContext.spuriousWakeupHazardDetector()
                    .recordWait(monitor, "fixture-monitor", false, Thread.currentThread());
            int guard = 0;
            while (!ready[0] && guard++ < 2) {
                try {
                    monitor.wait(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                ready[0] = true;
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_UPGRADE_DEADLOCK})
    void lockUpgradeDeadlock() {
        reachable("lockUpgradeDeadlockDetector()", AsyncTestContext::lockUpgradeDeadlockDetector);

        // ReentrantReadWriteLock cannot upgrade read -> write; attempting it deadlocks.
        // tryLock reproduces the attempt and returns instead of hanging.
        // A read lock cannot be upgraded: asking for the write lock while holding the read
        // lock can never succeed, and with two threads doing it neither can ever let go.
        var upgradeDetector = AsyncTestContext.lockUpgradeDeadlockDetector();
        ReentrantReadWriteLock rw = SHARED_RW_LOCK;
        rw.readLock().lock();
        upgradeDetector.recordReadLockAcquired(rw, "shared-rw-lock", Thread.currentThread());
        try {
            upgradeDetector.recordWriteLockAcquisitionAttempt(rw, "shared-rw-lock",
                    Thread.currentThread());
            if (rw.writeLock().tryLock()) {      // always false while the read lock is held
                rw.writeLock().unlock();
            }
        } finally {
            rw.readLock().unlock();
            upgradeDetector.recordReadLockReleased(rw, Thread.currentThread());
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.TRY_LOCK_MISUSE})
    void tryLockMisuse() {
        reachable("tryLockMisuseDetector()", AsyncTestContext::tryLockMisuseDetector);

        // The misuse is ignoring tryLock's result and unlocking anyway. Checking it is the
        // fix; the failure branch is what the detector reports on.
        // The misuse is ignoring what tryLock returned and carrying on as if the lock were
        // held - so the failed acquisition has to be recorded, not just the successful one.
        // The misuse is ignoring what tryLock returned and carrying on as if the lock were
        // held, so the fixture needs an acquisition that genuinely FAILS. A lock this worker
        // could take is no use: ReentrantLock is reentrant, and an uncontended tryLock always
        // succeeds. A helper thread therefore holds the lock for the duration, which makes the
        // failure deterministic rather than a race the scheduler might win either way.
        var tryLockDetector = AsyncTestContext.tryLockMisuseDetector();
        ReentrantLock contended = new ReentrantLock();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            contended.lock();
            held.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                contended.unlock();
            }
        }, "fixture-lock-holder");
        holder.setDaemon(true);
        holder.start();
        try {
            held.await(2, TimeUnit.SECONDS);
            boolean acquired = contended.tryLock();      // false: the holder still has it
            tryLockDetector.recordTryLockResult(contended, "contended-lock", acquired,
                    Thread.currentThread());
            if (acquired) {
                try {
                    spin(32);
                } finally {
                    contended.unlock();
                    tryLockDetector.recordUnlock(contended, "contended-lock",
                            Thread.currentThread());
                }
            } else {
                // The misuse itself: unlock() in a finally block that runs whether or not
                // tryLock() succeeded. Done for real, because the IllegalMonitorStateException
                // it throws IS the bug - a consumer writing this gets it at runtime, and
                // catching it here is what keeps the fixture from failing the round.
                spin(32);
                try {
                    contended.unlock();
                } catch (IllegalMonitorStateException expected) {
                    // Unlocking a lock this thread never acquired: the finding, reproduced.
                }
                tryLockDetector.recordUnlock(contended, "contended-lock", Thread.currentThread());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK})
    void completableFutureBlockingCallback() {
        reachable("cfBlockingCallbackDetector()", AsyncTestContext::cfBlockingCallbackDetector);

        // A join() inside a thenApply callback blocks a common-pool worker. This callback
        // stays non-blocking; the detector's subject is the shape, not this call.
        // Blocking inside a callback occupies the completing thread - on the common pool
        // that is one of a very small number of threads shared by the whole JVM.
        var callbackDetector = AsyncTestContext.cfBlockingCallbackDetector();
        CompletableFuture.completedFuture(1)
            .thenApply(value -> value + 1)
            .thenAccept(value -> {
                callbackDetector.recordEnterCallback("thenAccept", Thread.currentThread());
                callbackDetector.recordBlockingCall(Thread.currentThread(), "Thread.sleep");
                spin(value);
                callbackDetector.recordExitCallback(Thread.currentThread());
            })
            .join();
    }

    /** One lock for the whole round: an upgrade attempt needs two contending readers. */
    private static final ReentrantReadWriteLock SHARED_RW_LOCK = new ReentrantReadWriteLock();

}
