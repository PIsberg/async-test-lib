package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An adopted child joins its round at its first woven call, whichever call that is (#1495). Only
 * {@code point} and {@code acquiring} used to go on to do their own work after the join: a child
 * whose first call was a join, a wait for a task, a thread start or a monitor it already held joined
 * the round and then dropped the call itself, so the wait blocked outside the schedule, the
 * grandchild ran uncontrolled, or the monitor went unrecorded.
 *
 * <p>Driven through the calls woven code makes, on threads the test starts itself.
 */
class ControlledScheduleAdoptedFirstCallTest {

    private static final long WAIT_SECONDS = 10;
    private static final int SEEDS = 16;

    /** Runs {@code body} as the round's one worker and returns what it threw, or null. */
    private static Throwable runWorker(ControlledSchedule schedule, ThrowingRunnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                schedule.enter(0);
                body.run();
                schedule.leave();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "adopting-worker");
        worker.start();
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "the worker hung: " + schedule.summary());
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        return failure.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Thread adoptAndStart(Runnable body, String name) {
        Thread child = new Thread(body, name);
        ControlledSchedule.starting(child);
        child.start();
        return child;
    }

    @Test
    void aChildWhoseFirstCallJoinsASiblingWaitsForItInsideTheSchedule() throws Exception {
        // Before: joining() joined the child to the round and returned, so the real join() that
        // follows it blocked on a sibling that could not run while the child held the round, and
        // watch() counted the child escaped.
        for (long seed = 1; seed <= SEEDS; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(1, seed);
            try {
                Throwable failure = runWorker(schedule, () -> {
                    Thread sibling = adoptAndStart(ControlledSchedule::point, "sibling");
                    Thread child = adoptAndStart(() -> {
                        ControlledSchedule.joining(sibling); // its first woven call
                        try {
                            sibling.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, "joining-child");
                    ControlledSchedule.joining(child);
                    child.join();
                    sibling.join();
                });
                assertNull(failure, "seed " + seed);
                assertEquals(0, schedule.summary().escapes(),
                        "seed " + seed + ": nothing here blocks outside the schedule, " + schedule.summary());
            } finally {
                schedule.close();
            }
        }
    }

    @Test
    void aChildWhoseFirstCallWaitsForATaskWaitsForItInsideTheSchedule() throws Exception {
        // The same for a get on an adopted task's future: the real wait after awaitingTask() blocked
        // outside the schedule on a task that could not run while the child held the round.
        for (long seed = 1; seed <= SEEDS; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(1, seed);
            try {
                Throwable failure = runWorker(schedule, () -> {
                    Object adoption = ControlledSchedule.submitting();
                    CountDownLatch result = new CountDownLatch(1); // the future the child gets
                    Thread pool = new Thread(() -> {
                        Object started = ControlledSchedule.taskStarting(adoption);
                        ControlledSchedule.point();
                        result.countDown();
                        ControlledSchedule.taskEnded(started);
                    }, "pool-thread");
                    pool.start();
                    Thread child = adoptAndStart(() -> {
                        ControlledSchedule.awaitingTask(adoption); // its first woven call
                        try {
                            result.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, "getting-child");
                    ControlledSchedule.joining(child);
                    child.join();
                    pool.join();
                });
                assertNull(failure, "seed " + seed);
                assertEquals(0, schedule.summary().escapes(),
                        "seed " + seed + ": nothing here blocks outside the schedule, " + schedule.summary());
            } finally {
                schedule.close();
            }
        }
    }

    @Test
    void aChildWhoseFirstCallStartsAThreadAdoptsIt() throws Exception {
        // Before: starting() returned at once on a thread not yet in the round, so the grandchild was
        // never adopted and ran beside the child with no escape counted. Adopted, it waits at its
        // first point until the child gives the round up, so while the child runs on it cannot pass.
        ControlledSchedule schedule = ControlledSchedule.open(1, 7L);
        AtomicBoolean grandchildPassed = new AtomicBoolean();
        AtomicBoolean sawGrandchild = new AtomicBoolean();
        try {
            Throwable failure = runWorker(schedule, () -> {
                Thread child = adoptAndStart(() -> {
                    Thread grandchild = new Thread(() -> {
                        ControlledSchedule.point();
                        grandchildPassed.set(true);
                    }, "grandchild");
                    ControlledSchedule.starting(grandchild); // its first woven call
                    grandchild.start();
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                    while (!grandchildPassed.get() && System.nanoTime() < until) {
                        Thread.onSpinWait(); // busy, not blocked, and well inside the stall bound
                    }
                    sawGrandchild.set(grandchildPassed.get());
                    ControlledSchedule.joining(grandchild);
                    try {
                        grandchild.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "starting-child");
                ControlledSchedule.joining(child);
                child.join();
            });
            assertNull(failure);
            assertTrue(grandchildPassed.get(), "the grandchild never ran");
            // A child the stall bound counted escaped hands its round on, and then the grandchild
            // may pass: only a pass with no escape counted is uncontrolled.
            assertTrue(!sawGrandchild.get() || schedule.summary().escapes() > 0,
                    "the grandchild ran while the child held the round, with no escape counted: "
                            + schedule.summary());
        } finally {
            schedule.close();
        }
    }

    @Test
    void aChildWhoseFirstCallIsAMonitorItHoldsRecordsTheHold() throws Exception {
        // A synchronized method's first instruction is acquired(this), with the monitor already
        // taken. Before: acquired() returned at once on a thread not yet in the round, so the child
        // joined at its next point holding a monitor the schedule did not know of, and a grant gave
        // the worker's acquire of it the round, where the worker blocked: an escape.
        for (long seed = 1; seed <= SEEDS; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(1, seed);
            Object monitor = new Object();
            AtomicBoolean entered = new AtomicBoolean();
            try {
                Throwable failure = runWorker(schedule, () -> {
                    Thread child = adoptAndStart(() -> {
                        synchronized (monitor) {
                            entered.set(true);
                            ControlledSchedule.acquired(monitor); // its first woven call
                            ControlledSchedule.point();
                            ControlledSchedule.released(monitor);
                        }
                    }, "synchronized-child");
                    // Hold the round until the child waits for its turn inside the monitor.
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                    while (!(entered.get() && child.getState() == Thread.State.TIMED_WAITING)
                            && System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    ControlledSchedule.acquiring(monitor);
                    synchronized (monitor) {
                        ControlledSchedule.point();
                    }
                    ControlledSchedule.released(monitor);
                    ControlledSchedule.joining(child);
                    child.join();
                });
                assertNull(failure, "seed " + seed);
                assertEquals(0, schedule.summary().escapes(), "seed " + seed
                        + ": the worker's acquire waits for the child's recorded hold, " + schedule.summary());
            } finally {
                schedule.close();
            }
        }
    }
}
