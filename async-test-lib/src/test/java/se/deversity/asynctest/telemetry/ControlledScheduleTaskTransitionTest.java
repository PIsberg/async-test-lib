package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three state transitions around an adopted executor task (#1413), driven through the calls the
 * woven code makes: {@code submitting}, {@code taskStarting} and {@code taskEnded} on the task's
 * thread, {@code awaitingTask} before the submitter's {@code get}.
 */
class ControlledScheduleTaskTransitionTest {

    private static final long WAIT_SECONDS = 10;

    @Test
    void aTaskThatEscapedAndEndsWhileNobodyRunsHandsTheRoundToItsWaiter() throws Exception {
        // The task blocks without a point, so watch() counts it escaped and, with the submitter
        // waiting for it, nobody can run: the round is held by no one. Its end is its leave(), and
        // only a grant there gives the waiter the round back; without one the waiter waited until
        // the round timed out, since childEnded() sees threads, and a task has none.
        ControlledSchedule schedule = ControlledSchedule.open(1, 7L);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch gotItBack = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            schedule.enter(0);
            Object adoption = ControlledSchedule.submitting();
            Thread pool = new Thread(() -> {
                Object started = ControlledSchedule.taskStarting(adoption);
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    ControlledSchedule.taskEnded(started);
                }
            }, "pool-thread");
            pool.start();
            ControlledSchedule.awaitingTask(adoption);
            gotItBack.countDown();
            schedule.leave();
        }, "submitting-worker");
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (schedule.summary().escapes() < 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, schedule.summary().escapes(), "the blocked task escapes once");
            release.countDown();

            assertTrue(gotItBack.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the task ended while nobody held the round, and its waiter never got it back");
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        } finally {
            release.countDown();
            schedule.close();
        }
    }

    @Test
    void anInterruptedWaiterRunsOnFree_andIsNotCountedTwice() throws Exception {
        // The submitter is interrupted at the point before its get. awaitTurn() frees it and counts
        // one escape; the join wait after the point then made it ready again, took the round from
        // whoever held it, and waited once more, which the same interrupt ended with a second escape.
        for (long seed = 1; seed <= 16; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(1, seed);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    schedule.enter(0);
                    Object adoption = ControlledSchedule.submitting();
                    Thread pool = new Thread(() -> ControlledSchedule.taskEnded(
                            ControlledSchedule.taskStarting(adoption)), "pool-thread");
                    pool.start();
                    Thread.currentThread().interrupt();
                    ControlledSchedule.awaitingTask(adoption);
                    Thread.interrupted();
                    pool.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                    schedule.leave();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }, "interrupted-worker");
            try {
                worker.start();
                assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "seed " + seed + ": the worker hung");
                assertEquals(null, failure.get(), "seed " + seed);
                assertTrue(schedule.summary().escapes() <= 1,
                        "seed " + seed + ": one interrupt is one escape, was " + schedule.summary());
            } finally {
                schedule.close();
            }
        }
    }

    @Test
    void anAdoptedThreadThatRunsAnAdoptedTaskBeforeItsFirstPointLeavesNoOrphanSlot() throws Exception {
        // A pool thread built in woven code is adopted at its start, then begins in unwoven pool
        // code and reaches the task's taskStarting before any point. Joining the task's slot alone
        // left the thread's slot ready with nobody to fill it, and the grant that picked it saw
        // the thread waiting on the task's turn: a false escape.
        for (long seed = 1; seed <= 16; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(1, seed);
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                schedule.enter(0);
                Object[] adoption = new Object[1];
                Thread pool = new Thread(() -> {
                    Object started = ControlledSchedule.taskStarting(adoption[0]);
                    for (int i = 0; i < 3; i++) {
                        ControlledSchedule.point();
                    }
                    ControlledSchedule.taskEnded(started);
                }, "adopted-pool-thread");
                ControlledSchedule.starting(pool);
                adoption[0] = ControlledSchedule.submitting();
                pool.start();
                ControlledSchedule.awaitingTask(adoption[0]);
                ControlledSchedule.joining(pool);
                done.countDown();
                schedule.leave();
            }, "adopting-worker");
            try {
                worker.start();
                assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "seed " + seed + ": the worker hung");
                worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                assertEquals(0, schedule.summary().escapes(),
                        "seed " + seed + ": nothing here blocks outside the schedule, " + schedule.summary());
            } finally {
                schedule.close();
            }
        }
    }

    @Test
    void aTaskThatNeverStartsReleasesItsWaiterOnceItEscapes() throws Exception {
        // Submitted, never run: its executor is busy, shut down, or the future was cancelled while
        // queued. watchArriving() counts it escaped after the stall bound, but the waiter was still
        // held for it as though it would end: canProceed and grant() both wait for DONE, so nobody
        // could run and the round hung until it timed out (#1495).
        ControlledSchedule schedule = ControlledSchedule.open(1, 7L);
        CountDownLatch gotItBack = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            schedule.enter(0);
            Object adoption = ControlledSchedule.submitting();
            ControlledSchedule.awaitingTask(adoption);
            gotItBack.countDown();
            schedule.leave();
        }, "waiting-worker");
        try {
            worker.start();
            assertTrue(gotItBack.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the task escaped without ever starting, and its waiter never got the round back");
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertEquals(1, schedule.summary().escapes(), "the task that never ran is the one escape: "
                    + schedule.summary());
        } finally {
            schedule.close();
        }
    }

    @Test
    void aPoolThreadAdoptedInAnEarlierRoundJoinsTheNextRoundsTask() throws Exception {
        // A pool thread built in woven code during round 1 is adopted there and joins at its first
        // point, which sets its CURRENT. Adopted threads never leave(), and a closed schedule did not
        // clear it, so in round 2 the same thread's taskStarting read the stale CURRENT as "already
        // in a round", the caller-runs case, marked the task's slot done at once and ran it
        // uncontrolled, while round 2 reported no escape (#1495).
        CountDownLatch joinedFirstRound = new CountDownLatch(1);
        CountDownLatch nextJob = new CountDownLatch(1);
        AtomicReference<Object> secondAdoption = new AtomicReference<>();
        AtomicReference<Object> started = new AtomicReference<>("not run");
        CountDownLatch taskRan = new CountDownLatch(1);
        Thread pool = new Thread(() -> {
            ControlledSchedule.point(); // its first point: joins round 1 as the adopted child
            joinedFirstRound.countDown();
            try {
                nextJob.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Object begun = ControlledSchedule.taskStarting(secondAdoption.get());
            started.set(begun);
            ControlledSchedule.point();
            ControlledSchedule.taskEnded(begun);
            taskRan.countDown();
        }, "reused-pool-thread");

        ControlledSchedule first = ControlledSchedule.open(1, 7L);
        Thread firstWorker = new Thread(() -> {
            first.enter(0);
            ControlledSchedule.starting(pool);
            pool.start();
            try {
                joinedFirstRound.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            first.leave();
        }, "first-round-worker");
        firstWorker.start();
        firstWorker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        first.close();

        ControlledSchedule second = ControlledSchedule.open(1, 7L);
        try {
            Thread secondWorker = new Thread(() -> {
                second.enter(0);
                secondAdoption.set(ControlledSchedule.submitting());
                nextJob.countDown();
                ControlledSchedule.awaitingTask(secondAdoption.get());
                second.leave();
            }, "second-round-worker");
            secondWorker.start();
            assertTrue(taskRan.await(WAIT_SECONDS, TimeUnit.SECONDS), "the task never ran");
            secondWorker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertTrue(started.get() != null, "the reused pool thread's task never joined round 2: its CURRENT"
                    + " from the closed round 1 made it look like a caller-runs thread, " + second.summary());
        } finally {
            nextJob.countDown();
            second.close();
            pool.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
    }
}
