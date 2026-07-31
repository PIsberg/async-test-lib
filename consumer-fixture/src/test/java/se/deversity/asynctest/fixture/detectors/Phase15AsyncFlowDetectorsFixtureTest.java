package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_OBTRUDE_ABUSE})
    void completableFutureObtrudeAbuse() {
        reachable("completableFutureObtrudeDetector()",
            AsyncTestContext::completableFutureObtrudeDetector);

        // obtrudeValue overwrites an already-published result, so downstream stages can
        // observe two different values for one future.
        CompletableFuture<String> future = CompletableFuture.completedFuture("first");
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
        Object monitor = new Object();
        boolean[] ready = {false};
        synchronized (monitor) {
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
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.readLock().lock();
        try {
            if (rw.writeLock().tryLock()) {      // always false while the read lock is held
                rw.writeLock().unlock();
            }
        } finally {
            rw.readLock().unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.TRY_LOCK_MISUSE})
    void tryLockMisuse() {
        reachable("tryLockMisuseDetector()", AsyncTestContext::tryLockMisuseDetector);

        // The misuse is ignoring tryLock's result and unlocking anyway. Checking it is the
        // fix; the failure branch is what the detector reports on.
        ReentrantLock lock = new ReentrantLock();
        try {
            if (lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    spin(32);
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK})
    void completableFutureBlockingCallback() {
        reachable("cfBlockingCallbackDetector()", AsyncTestContext::cfBlockingCallbackDetector);

        // A join() inside a thenApply callback blocks a common-pool worker. This callback
        // stays non-blocking; the detector's subject is the shape, not this call.
        CompletableFuture.completedFuture(1)
            .thenApply(value -> value + 1)
            .thenAccept(value -> spin(value))
            .join();
    }
}
