package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ReentrantLockDetector;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #855, with the agent attached: the lock's real owner decides whether a hold at analysis is a leak.
 *
 * <p>Without the agent the detector knows a holder only by the name the lock prints, and an
 * unnamed virtual thread is {@code ""}, so a hold it leaked through a balanced re-entry is printed
 * as context. The agent opens {@code java.util.concurrent.locks} to the library, which can then
 * read the owner {@code Thread} itself: one that has terminated holding the lock can never release
 * it, whatever its name, and one still alive may.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM. Default mode: the opening does not need
 * field weaving.
 */
@Tag("e2e")
class LockOwnerIdentityTest {

    @BeforeAll
    static void attach() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture");
    }

    @Test
    @DisplayName("an unnamed virtual thread that ended holding a re-entered lock leaked it")
    void anUnnamedThreadThatEndedHoldingTheLockLeakedIt() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "reentered");
        Thread leaker = Thread.ofVirtual().unstarted(() -> {
            lock.lock();
            lock.lock(); // the helper's extra hold, never given back
            detector.recordLockAcquired(lock, "");
            lock.unlock();
            detector.recordLockReleased(lock, "");
        });
        leaker.start();
        leaker.join();

        assertTrue(lock.isLocked(), "precondition: the thread ended still holding the lock");
        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the lock's owner is a terminated thread, which can never release it, so it is "
                        + "leaked although the thread has no name: " + report);
    }

    @Test
    @DisplayName("an unnamed virtual thread still working under the lock is context, not a leak")
    void anUnnamedThreadStillWorkingUnderTheLockIsNotALeak() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "busy");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().start(() -> {
            lock.lock();
            try {
                holding.countDown();
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the worker never took the lock");
            ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the owner is alive and may release the lock: " + report);
        } finally {
            done.countDown();
            worker.join();
        }
    }
}
