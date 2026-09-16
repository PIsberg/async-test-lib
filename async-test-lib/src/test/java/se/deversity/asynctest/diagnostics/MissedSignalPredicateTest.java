package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MissedSignalDetector#recordWait(Object, boolean)} against real monitors (#599).
 *
 * <p>Without the predicate the detector could not tell a polling loop whose timed wait runs out by
 * design from a lost wakeup, and it consulted only the notify just before a wait, so a delivered
 * notify hid an earlier lost one. A caller that says whether the wait is predicate-guarded gets
 * both decided: a guarded wait is never a missed signal, because its loop re-tests the state a
 * lost notify would have changed; an unguarded wait is judged against every notify lost before it.
 */
@DisplayName("MissedSignalDetector with the predicate recorded (#599)")
class MissedSignalPredicateTest {

    private static final long JOIN_MS = 10_000;

    private final Object monitor = new Object();
    private boolean queueEmpty = true;

    @Test
    @DisplayName("a guarded poll that times out after the last producer's lost notify stays silent")
    void aGuardedPollThatTimesOutAfterALostNotifyStaysSilent() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordNotify(monitor); // the last producer: nobody is waiting yet
                monitor.notifyAll();
            }
        });
        runAndJoin(() -> {
            synchronized (monitor) {
                int polls = 0;
                while (queueEmpty && polls++ < 2) {
                    detector.recordWait(monitor, true);
                    monitor.wait(20); // runs out by design: the consumer gives up when idle
                    detector.recordWakeup(monitor);
                }
            }
        });

        assertFalse(detector.analyze().hasIssues(),
                "the consumer re-tests the queue after every wait, so the notify it did not see "
                        + "cannot strand it; a poll that runs out is how it ends:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("a guarded wait still open after a lost notify stays silent")
    void aGuardedWaitStillOpenAfterALostNotifyStaysSilent() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> detector.recordNotify(monitor));
        runAndJoin(() -> detector.recordWait(monitor, true));

        assertFalse(detector.analyze().hasIssues(),
                "a guarded loop that is still waiting re-tested its predicate after the notify and "
                        + "found it false; whatever keeps it waiting is not a lost signal:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("a lost notify, then one delivered elsewhere, does not hide a later unguarded wait")
    void aDeliveredNotifyDoesNotHideAnEarlierLostOneFromAnUnguardedWait() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> detector.recordNotify(monitor)); // lost: nobody waiting
        deliverOneNotifyToAWaiter(detector);
        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordWait(monitor, false);
                monitor.wait(20); // no predicate: in production this is wait() and never returns
                detector.recordWakeup(monitor);
            }
        });

        assertTrue(detector.analyze().hasIssues(),
                "the first notify went to nobody, the second was consumed by the first waiter, "
                        + "and the unguarded wait after both received nothing: the lost wakeup");
    }

    @Test
    @DisplayName("an unguarded wait that times out with no notify ever lost stays silent")
    void anUnguardedWaitWithNoLostNotifyStaysSilent() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        deliverOneNotifyToAWaiter(detector);
        runAndJoin(() -> {
            detector.recordWait(monitor, false);
            detector.recordWakeup(monitor);
        });

        assertFalse(detector.analyze().hasIssues(),
                "every notify reached a waiter, so no signal was lost; a wait nobody notified is "
                        + "a missing notify, not this finding:\n" + detector.analyze());
    }

    @Test
    @DisplayName("the recordWait form that does not say keeps the #586 rule, both of its boundaries")
    void theFormThatDoesNotSayKeepsTheOldRule() throws Exception {
        MissedSignalDetector guardedPoll = new MissedSignalDetector();
        runAndJoin(() -> guardedPoll.recordNotify(monitor));
        runAndJoin(() -> {
            guardedPoll.recordWait(monitor);
            guardedPoll.recordWakeup(monitor);
        });
        assertTrue(guardedPoll.analyze().hasIssues(),
                "without the predicate a timed wait after a lost notify is still reported; callers "
                        + "who poll must use recordWait(monitor, true)");

        MissedSignalDetector hidden = new MissedSignalDetector();
        runAndJoin(() -> hidden.recordNotify(monitor));
        deliverOneNotifyToAWaiter(hidden);
        runAndJoin(() -> {
            hidden.recordWait(monitor);
            hidden.recordWakeup(monitor);
        });
        assertFalse(hidden.analyze().hasIssues(),
                "without the predicate only the notify just before a wait is consulted, so the "
                        + "earlier lost notify stays hidden; changing that is a deliberate model change");
    }

    @Test
    @DisplayName("an observed predicate re-check via recordPredicateCheck silences guarded poll after lost notify (#635)")
    void observedPredicateRecheckSilencesGuardedPollAfterLostNotify() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordNotify(monitor);
                monitor.notifyAll();
            }
        });
        runAndJoin(() -> {
            synchronized (monitor) {
                int polls = 0;
                while (queueEmpty && polls++ < 2) {
                    detector.recordPredicateCheck(monitor, !queueEmpty);
                    detector.recordWait(monitor);
                    monitor.wait(20);
                    detector.recordWakeup(monitor);
                    detector.recordPredicateCheck(monitor, !queueEmpty);
                }
            }
        });

        assertFalse(detector.analyze().hasIssues(),
                "observed predicate check re-evaluated after wait, so wait is guarded and stays silent (#635):\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("an unguarded wait without predicate check fires after lost notify (#635)")
    void unguardedWaitWithoutPredicateCheckFiresAfterLostNotify() throws Exception {
        MissedSignalDetector detector = new MissedSignalDetector();

        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordNotify(monitor);
                monitor.notifyAll();
            }
        });
        runAndJoin(() -> {
            synchronized (monitor) {
                detector.recordWait(monitor);
                monitor.wait(20);
                detector.recordWakeup(monitor);
            }
        });

        assertTrue(detector.analyze().hasIssues(),
                "an unguarded wait without predicate check fires after lost notify (#635)");
    }

    /** One waiter records a wait, another thread notifies while it waits, the waiter wakes. */
    private void deliverOneNotifyToAWaiter(MissedSignalDetector detector) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        Worker waiter = start(() -> {
            detector.recordWait(monitor, false);
            waiting.countDown();
            assertTrue(notified.await(JOIN_MS, TimeUnit.MILLISECONDS));
            detector.recordWakeup(monitor);
        });
        assertTrue(waiting.await(JOIN_MS, TimeUnit.MILLISECONDS));
        runAndJoin(() -> detector.recordNotify(monitor));
        notified.countDown();
        waiter.join();
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
