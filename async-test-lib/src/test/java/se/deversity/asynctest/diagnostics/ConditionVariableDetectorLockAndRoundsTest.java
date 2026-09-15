package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two follow-ups to the per-await model of {@link ConditionVariableDetector} (#583).
 *
 * <p><strong>#592, the lock decides stuck waiters.</strong> When a condition is registered
 * together with the lock that created it, a stuck waiter is a thread the lock reports parked on
 * the condition at analysis, not an await the body recorded and never closed.
 *
 * <p><strong>#593, rounds.</strong> A platform worker is reused by the next round. An await it
 * recorded in one round and never exited must not be merged into its await in the next round.
 *
 * <p>Recordings run on single-thread executors, so "waiter A" is one real thread that is reused
 * exactly the way a pooled worker is, and every step happens in the order written.
 */
@DisplayName("ConditionVariableDetector: the lock's wait queue (#592) and round boundaries (#593)")
class ConditionVariableDetectorLockAndRoundsTest {

    private static final String NAME = "data-ready";

    private final ExecutorService waiterA = Executors.newSingleThreadExecutor();
    private final ExecutorService producer = Executors.newSingleThreadExecutor();
    private final ConditionVariableDetector detector = new ConditionVariableDetector();

    @AfterEach
    void shutDown() {
        waiterA.shutdownNow();
        producer.shutdownNow();
    }

    private static void on(ExecutorService thread, Runnable step)
            throws InterruptedException, ExecutionException, TimeoutException {
        thread.submit(step).get(10, TimeUnit.SECONDS);
    }

    /** Starts a daemon thread that parks in {@code condition.await()} and returns once it is parked. */
    private static Thread parkOn(java.util.concurrent.locks.Lock lock, Condition condition,
            java.util.function.BooleanSupplier parked) throws InterruptedException {
        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                condition.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!parked.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "the waiter never parked");
            Thread.onSpinWait();
        }
        return waiter;
    }

    private static boolean hasWaiters(ReentrantLock lock, Condition condition) {
        lock.lock();
        try {
            return lock.hasWaiters(condition);
        } finally {
            lock.unlock();
        }
    }

    @Nested
    @DisplayName("#593: an await is not merged across a round boundary")
    class Rounds {

        private final Condition condition = new ReentrantLock().newCondition();

        @Test
        @DisplayName("an await abandoned in one round is still reported after the same thread awaits in the next")
        void anAwaitAbandonedInAnEarlierRoundIsStillReported() throws Exception {
            detector.registerCondition(condition, NAME);
            // Round one: the body records its await and throws before it records the exit.
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            detector.markInvocationStart();
            // Round two, same pooled thread: a complete, signalled wait.
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            on(producer, () -> detector.recordSignal(condition, NAME, false));
            on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));

            var report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "round one's await never exited; round two's exit closes round two's await, "
                            + "not that one. Report:\n" + report);
            assertEquals(1, report.stuckWaiters.size(), report.toString());
            assertTrue(report.stuckWaiters.get(0).contains("earlier round"), report.toString());
        }

        @Test
        @DisplayName("a signal owed to an abandoned await does not credit the next round's await")
        void aSignalOwedToAnAbandonedAwaitDoesNotCreditTheNextRound() throws Exception {
            detector.registerCondition(condition, NAME);
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            on(producer, () -> detector.recordSignal(condition, NAME, false));   // owed to round one
            detector.markInvocationStart();
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false)); // nothing signalled it

            var report = detector.analyze();
            assertEquals(1, report.missingSignals.size(),
                    "round two's await started after the only signal and returned as woken with "
                            + "nothing signalling it. Report:\n" + report);
        }

        @Test
        @DisplayName("a loop that awaits again within one round, recording one exit at the end, stays silent")
        void aLoopThatReAwaitsWithinOneRoundIsTheSameWait() throws Exception {
            detector.registerCondition(condition, NAME);
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            on(producer, () -> detector.recordSignal(condition, NAME, false));
            on(waiterA, () -> detector.recordAwait(condition, NAME));            // predicate still false
            on(producer, () -> detector.recordSignal(condition, NAME, false));
            on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));
            detector.markInvocationStart();

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "a while loop that re-awaits and records its exit once, after the loop, is one "
                            + "wait; nothing was abandoned. Report:\n" + report);
        }

        @Test
        @DisplayName("a waiter parked across a round boundary and signalled later is not abandoned")
        void aWaiterSpanningARoundBoundaryIsNotAbandoned() throws Exception {
            detector.registerCondition(condition, NAME);
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            detector.markInvocationStart();
            on(producer, () -> detector.recordSignal(condition, NAME, false));
            on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "a consumer the body started may wait through a round boundary and be woken "
                            + "in the next round; the boundary alone ends nothing. Report:\n" + report);
        }
    }

    @Nested
    @DisplayName("#592: with its lock registered, a stuck waiter is read from the lock")
    class Lock {

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();

        @Test
        @DisplayName("a thread parked on the condition at analysis fires, even with no await recorded")
        void aThreadParkedAtAnalysisIsReadFromTheLock() throws Exception {
            detector.registerCondition(lock, condition, NAME);
            Thread waiter = parkOn(lock, condition, () -> hasWaiters(lock, condition));
            try {
                var report = detector.analyze();
                assertTrue(report.hasIssues(),
                        "the lock reports a thread parked on the condition that nothing signalled. "
                                + "Report:\n" + report);
                assertEquals(1, report.stuckWaiters.size(), report.toString());
                assertTrue(report.stuckWaiters.get(0).contains("read from the lock"), report.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }

        @Test
        @DisplayName("a recorded await the lock shows nobody parked in is a note, not a stuck waiter")
        void aRecordedAwaitThatNeverParkedIsNotAFinding() throws Exception {
            detector.registerCondition(lock, condition, NAME);
            // The body recorded the await, then found its predicate true and never called await().
            on(waiterA, () -> detector.recordAwait(condition, NAME));

            var report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the lock shows no thread parked on the condition, so the recorded await is the "
                            + "body's declaration, not a stuck waiter. Report:\n" + report);
            assertTrue(report.toString().contains("never exited"), report.toString());
        }

        @Test
        @DisplayName("a lock held by another thread at analysis is not queried and reports nothing")
        void aLockHeldAtAnalysisIsNotQueried() throws Exception {
            detector.registerCondition(lock, condition, NAME);
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                lock.lock();
                try {
                    held.countDown();
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            });
            holder.setDaemon(true);
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS));
            try {
                var report = detector.analyze();
                assertFalse(report.hasIssues(),
                        "the waiters of a lock that cannot be taken cannot be read; silence is the "
                                + "safe direction. Report:\n" + report);
                assertTrue(report.toString().contains("could not be read"), report.toString());
            } finally {
                release.countDown();
                holder.join(10_000);
            }
        }

        @Test
        @DisplayName("a condition registered with a lock that did not create it is not queried")
        void aConditionOfAnotherLockIsNotQueried() throws Exception {
            ReentrantLock other = new ReentrantLock();
            detector.registerCondition(other, condition, NAME);
            Thread waiter = parkOn(lock, condition, () -> hasWaiters(lock, condition));
            try {
                var report = detector.analyze();
                assertFalse(report.hasIssues(), "the registered lock does not own the condition. Report:\n"
                        + report);
                assertTrue(report.toString().contains("does not own"), report.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }

        @Test
        @DisplayName("a woken and joined waiter stays silent (the twin of the parked one)")
        void aWokenWaiterStaysSilent() throws Exception {
            detector.registerCondition(lock, condition, NAME);
            Thread waiter = parkOn(lock, condition, () -> hasWaiters(lock, condition));
            lock.lock();
            try {
                detector.recordSignal(condition, NAME, false);
                condition.signal();
            } finally {
                lock.unlock();
            }
            waiter.join(10_000);

            var report = detector.analyze();
            assertFalse(report.hasIssues(), "the only waiter was signalled and left. Report:\n" + report);
        }

        @Test
        @DisplayName("a write-lock condition of a ReentrantReadWriteLock is read the same way")
        void aReadWriteLockConditionIsReadFromTheLock() throws Exception {
            ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
            Condition writeCondition = rw.writeLock().newCondition();
            detector.registerCondition(rw, writeCondition, NAME);
            Thread waiter = parkOn(rw.writeLock(), writeCondition, () -> {
                rw.writeLock().lock();
                try {
                    return rw.hasWaiters(writeCondition);
                } finally {
                    rw.writeLock().unlock();
                }
            });
            try {
                var report = detector.analyze();
                assertEquals(1, report.stuckWaiters.size(), report.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }

        @Test
        @DisplayName("registering the lock after a plain registration keeps what was recorded")
        void registeringTheLockLaterKeepsTheRecordedState() throws Exception {
            detector.registerCondition(condition, NAME);
            on(waiterA, () -> detector.recordAwait(condition, NAME));
            on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));   // no signal
            detector.registerCondition(lock, condition, NAME);

            var report = detector.analyze();
            assertEquals(1, report.missingSignals.size(),
                    "a later registration with the lock must not discard the recorded missing "
                            + "signal. Report:\n" + report);
        }

        @Test
        @DisplayName("#643: an idle consumer parked on a condition whose predicate is false stays silent")
        void idleConsumerParkedOnConditionStaysSilent() throws Exception {
            boolean[] queueEmpty = {true};
            detector.registerCondition(lock, condition, () -> !queueEmpty[0], NAME);
            Thread waiter = parkOn(lock, condition, () -> hasWaiters(lock, condition));
            try {
                var report = detector.analyze();
                assertFalse(report.hasIssues(),
                        "the consumer is parked on an empty queue (predicate is false); it is an idle "
                                + "consumer, not a stuck waiter. Report:\n" + report);
                assertEquals(0, report.stuckWaiters.size(), report.toString());
                assertTrue(report.toString().contains("idle consumer"), report.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }

        @Test
        @DisplayName("#643: a consumer parked on a condition whose predicate is satisfied is reported as a stuck waiter")
        void consumerParkedWhilePredicateSatisfiedFires() throws Exception {
            boolean[] queueNotEmpty = {true};
            detector.registerCondition(lock, condition, () -> queueNotEmpty[0], NAME);
            Thread waiter = parkOn(lock, condition, () -> hasWaiters(lock, condition));
            try {
                var report = detector.analyze();
                assertTrue(report.hasIssues(),
                        "the consumer is parked while its predicate holds (an item was ready but no signal woke it). "
                                + "Report:\n" + report);
                assertEquals(1, report.stuckWaiters.size(), report.toString());
                assertTrue(report.stuckWaiters.get(0).contains("while its predicate is satisfied"), report.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }

        @Test
        @DisplayName("#643: write-lock condition with predicate: idle consumer stays silent, satisfied predicate fires")
        void writeLockConditionWithPredicate() throws Exception {
            ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
            Condition writeCondition = rw.writeLock().newCondition();
            boolean[] ready = {false};
            detector.registerCondition(rw, writeCondition, () -> ready[0], NAME);
            Thread waiter = parkOn(rw.writeLock(), writeCondition, () -> {
                rw.writeLock().lock();
                try {
                    return rw.hasWaiters(writeCondition);
                } finally {
                    rw.writeLock().unlock();
                }
            });
            try {
                var report = detector.analyze();
                assertFalse(report.hasIssues(), "predicate is false, so waiter is idle. Report:\n" + report);

                ready[0] = true;
                var reportAfterReady = detector.analyze();
                assertTrue(reportAfterReady.hasIssues(),
                        "predicate is true, so waiter is stuck. Report:\n" + reportAfterReady);
                assertEquals(1, reportAfterReady.stuckWaiters.size(), reportAfterReady.toString());
                assertTrue(reportAfterReady.stuckWaiters.get(0).contains("while its predicate is satisfied"),
                        reportAfterReady.toString());
            } finally {
                waiter.interrupt();
                waiter.join(10_000);
            }
        }
    }
}
