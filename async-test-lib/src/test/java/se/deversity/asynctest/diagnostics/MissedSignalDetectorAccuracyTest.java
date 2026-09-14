package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MissedSignalDetector} against real monitors on real threads (#586).
 *
 * <p>A notify that finds nobody waiting is not a defect on its own: a waiter that checks a state
 * predicate never waits for it. What loses a signal is a wait that begins after that notify and
 * receives no notify of its own. These cases pin both directions, and the ordering defect in the
 * old waiter count, where a wakeup from a thread that never waited erased a live waiter.
 */
@DisplayName("MissedSignalDetector accuracy (#586)")
class MissedSignalDetectorAccuracyTest {

    private static final long JOIN_MS = 10_000;

    private final Object monitor = new Object();
    private boolean dataReady;

    @Test
    @DisplayName("the class javadoc's flag-then-notify handshake stays silent")
    void predicateHandshakeWithTheNotifyFirstStaysSilent() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> {
            synchronized (monitor) {
                dataReady = true;
                detector.recordNotify("dataReady");
                monitor.notify();
            }
        });
        runAndJoin(() -> {
            synchronized (monitor) {
                while (!dataReady) {
                    detector.recordWait("dataReady");
                    monitor.wait();
                    detector.recordWakeup("dataReady");
                }
            }
        });

        assertFalse(detector.analyze().hasIssues(),
                "the waiter finds dataReady already true and never waits, so no signal was "
                        + "lost; this is the fix the report prescribes, reported as the bug:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("a notify before an unguarded wait that nothing wakes fires")
    void notifyBeforeAnUnguardedWaitFires() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordNotify("ready");
                monitor.notify();
            }
        });
        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordWait("ready");
                monitor.wait(20); // no predicate: in production this is wait() and never returns
                detector.recordWakeup("ready");
            }
        });

        assertTrue(detector.analyze().hasIssues(),
                "the notify went to nobody and the wait that followed received nothing: that "
                        + "is the lost wakeup itself");
    }

    @Test
    @DisplayName("a wait still open at analysis after a lost notify fires")
    void aWaitStillBlockedAfterALostNotifyFires() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> detector.recordNotify("ready"));
        runAndJoin(() -> detector.recordWait("ready")); // never woken: blocked forever

        assertTrue(detector.analyze().hasIssues(),
                "a waiter that arrived after the lost notify and is still waiting is the "
                        + "'blocks forever' half of the bug");
    }

    @Test
    @DisplayName("a lost notify followed by a wait that is signalled stays silent")
    void aLaterWaitThatIsSignalledStaysSilent() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);

        runAndJoin(() -> detector.recordNotify("ready"));
        Worker waiter = start(() -> {
            detector.recordWait("ready");
            waiting.countDown();
            assertTrue(notified.await(JOIN_MS, TimeUnit.MILLISECONDS));
            detector.recordWakeup("ready");
        });
        assertTrue(waiting.await(JOIN_MS, TimeUnit.MILLISECONDS));
        runAndJoin(() -> detector.recordNotify("ready"));
        notified.countDown();
        waiter.join();

        assertFalse(detector.analyze().hasIssues(),
                "the waiter was woken by a notify of its own, so no signal it needed was lost:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("a wakeup from a thread that never waited does not erase a live waiter")
    void anUnmatchedWakeupDoesNotEraseALiveWaiter() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Worker waiter = start(() -> {
            detector.recordWait("ready");
            waiting.countDown();
            assertTrue(release.await(JOIN_MS, TimeUnit.MILLISECONDS));
            detector.recordWakeup("ready");
        });
        assertTrue(waiting.await(JOIN_MS, TimeUnit.MILLISECONDS));
        runAndJoin(() -> detector.recordWakeup("ready")); // this thread never recorded a wait
        runAndJoin(() -> detector.recordNotify("ready")); // the waiter is still waiting
        release.countDown();
        waiter.join();
        // A later wait that times out: silent only if that notify is still known to have been
        // delivered. Were the stray wakeup to erase the live waiter, the notify would read as lost
        // and this wait as the one that missed it.
        runAndJoin(() -> {
            detector.recordWait("ready");
            detector.recordWakeup("ready");
        });

        assertFalse(detector.analyze().hasIssues(),
                "a thread was waiting when the notify arrived; a stray wakeup from another "
                        + "thread must not make that notify read as lost:\n" + detector.analyze());
    }

    // ---- harness ----

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /** A started thread whose failure is rethrown on {@link #join()} instead of vanishing. */
    private static final class Worker {
        private final Thread thread;
        private final AtomicReference<Throwable> died = new AtomicReference<>();

        Worker(Body body) {
            thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable failure) {
                    died.set(failure);
                }
            });
        }

        void join() throws InterruptedException {
            thread.join(JOIN_MS);
            assertFalse(thread.isAlive(), "worker did not finish within " + JOIN_MS + " ms");
            if (died.get() != null) {
                throw new AssertionError("a worker failed instead of completing its recordings",
                        died.get());
            }
        }
    }

    private static Worker start(Body body) {
        Worker worker = new Worker(body);
        worker.thread.start();
        return worker;
    }

    private static void runAndJoin(Body body) throws InterruptedException {
        start(body).join();
    }
}
