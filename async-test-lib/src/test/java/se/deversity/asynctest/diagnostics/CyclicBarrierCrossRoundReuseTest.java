package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reuse of a broken barrier that spans rounds (#693). With {@code useVirtualThreads = true} each
 * body execution is a fresh virtual thread, so "the same party came back" cannot be asked of a
 * thread. The runner's worker slot is the identity that survives, and it is used for virtual
 * threads only: a platform-thread run keeps the thread-id parties it always had.
 *
 * <p>Each helper below is one round's worker: a new thread, with a slot installed the way the
 * runner installs it, that does its recording and ends.
 */
class CyclicBarrierCrossRoundReuseTest {

    private final CyclicBarrierDetector detector = new CyclicBarrierDetector();
    private final AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().build());

    @Test
    @DisplayName("virtual threads: slot 0 sees the break in one round and awaits it again in the next")
    void reuseAcrossRoundsOnVirtualThreadsIsReported() throws Exception {
        CyclicBarrier barrier = registeredBrokenBarrier();

        asWorker(true, 0, () -> detector.recordBroken(barrier));
        asWorker(true, 0, () -> detector.recordAwait(barrier));

        assertTrue(detector.analyze().getReuseAfterBrokenBarriers().contains(barrier),
                "Slot 0 saw this barrier broken and came back to it with no reset in between. The "
                        + "second round is a different virtual thread, which is exactly the gap.");
    }

    @Test
    @DisplayName("virtual threads: the same two rounds with a reset in between stay silent")
    void resetBetweenRoundsIsNotReported() throws Exception {
        CyclicBarrier barrier = registeredBrokenBarrier();

        asWorker(true, 0, () -> detector.recordBroken(barrier));
        asWorker(true, 0, () -> {
            barrier.reset();
            detector.recordReset(barrier);
            detector.recordAwait(barrier);
        });

        assertFalse(detector.analyze().hasIssues(), "a reset barrier is whole again");
    }

    @Test
    @DisplayName("virtual threads: a different slot meeting the broken barrier is a first sight, not reuse")
    void anotherSlotIsAnotherParty() throws Exception {
        CyclicBarrier barrier = registeredBrokenBarrier();

        asWorker(true, 0, () -> detector.recordBroken(barrier));
        asWorker(true, 1, () -> detector.recordAwait(barrier));

        assertFalse(detector.analyze().getReuseAfterBrokenBarriers().contains(barrier),
                "Slot 1 never saw the break; a party cannot know a barrier is broken until its "
                        + "own await says so.");
    }

    @Test
    @DisplayName("platform threads: parties stay keyed by thread, so two threads sharing a slot are two parties")
    void platformThreadsKeepThreadIdentity() throws Exception {
        CyclicBarrier barrier = registeredBrokenBarrier();

        asWorker(false, 0, () -> detector.recordBroken(barrier));
        asWorker(false, 0, () -> detector.recordAwait(barrier));

        assertFalse(detector.analyze().getReuseAfterBrokenBarriers().contains(barrier),
                "Slot identity is for virtual threads only; a platform run must report what it "
                        + "reported before #693.");
    }

    private CyclicBarrier registeredBrokenBarrier() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        assertThrows(TimeoutException.class, () -> barrier.await(1, TimeUnit.NANOSECONDS));
        detector.registerBarrier(barrier, "shared-across-rounds", 2);
        return barrier;
    }

    /** Runs {@code body} to completion on a new thread, with {@code slot} installed as the runner does. */
    private void asWorker(boolean virtual, int slot, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable work = () -> {
            AsyncTestContext.install(context, slot);
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        };
        Thread thread = virtual ? Thread.ofVirtual().unstarted(work) : Thread.ofPlatform().unstarted(work);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }
    }
}
