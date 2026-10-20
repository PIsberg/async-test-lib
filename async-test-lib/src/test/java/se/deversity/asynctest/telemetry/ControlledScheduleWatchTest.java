package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code watch()} runs in a waiting worker that holds the schedule's own lock, and must not count
 * the running worker as escaped when all it waits for is that lock (#1065).
 *
 * <p>The running worker sets its {@code inside} flag before it takes the lock, and {@code watch()}
 * reads the flag before the thread state, so a worker that entered the schedule's code between the
 * two reads was seen {@code WAITING} with the flag still false and counted as an escape. The window
 * is nanoseconds wide, so a stress test would almost never hit it; this test builds the state the
 * window leaves behind instead: the running worker queued on the schedule's lock with the flag
 * false, while the test holds the lock and calls {@code watch()} as a waiting worker would. That
 * needs the private lock and method, read by reflection.
 */
class ControlledScheduleWatchTest {

    @Test
    void aWorkerQueuedOnTheSchedulesOwnLockHasNotEscaped() throws Exception {
        ControlledSchedule schedule = ControlledSchedule.open(1, 7L);
        Field lockField = ControlledSchedule.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(schedule);
        Method watch = ControlledSchedule.class.getDeclaredMethod("watch");
        watch.setAccessible(true);

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch queue = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            schedule.enter(0);
            running.countDown();
            try {
                queue.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // What a point does just after watch() read inside: take the schedule's lock. The
            // flag is still false here, which is the state the race leaves.
            lock.lock();
            lock.unlock();
            schedule.leave();
        }, "running-worker");
        worker.start();
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS), "the only worker gets the first turn");
            lock.lock();
            try {
                queue.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                // Queued, not merely still waiting on the latch, which is WAITING too.
                while (!(lock.hasQueuedThread(worker) && worker.getState() == Thread.State.WAITING)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(lock.hasQueuedThread(worker), "the worker queues on the schedule's lock");

                watch.invoke(schedule);

                assertEquals(0, schedule.summary().escapes(),
                        "the running worker waits for nothing but the schedule's own lock, which"
                                + " the watching worker holds; that is not an escape");
            } finally {
                lock.unlock();
            }
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            schedule.close();
        }
    }

    @Test
    void anArrivingChildQueuedOnTheSchedulesOwnLockHasNotEscaped() throws Exception {
        // The same race on the child path (#1072): a child granted the turn arrives through join(),
        // which takes the schedule's lock before it fills its slot, so watch() takes the
        // watchArriving branch and must not read that wait as an escape.
        ControlledSchedule schedule = ControlledSchedule.open(1, 7L);
        Field lockField = ControlledSchedule.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(schedule);
        Method watch = ControlledSchedule.class.getDeclaredMethod("watch");
        watch.setAccessible(true);

        Thread child = new Thread(() -> {
            // What join() does first: take the schedule's lock, before the child has a slot.
            lock.lock();
            lock.unlock();
        }, "arriving-child");
        Thread worker = new Thread(() -> {
            schedule.enter(0);
            ControlledSchedule.starting(child);
            schedule.leave(); // the round goes to the only ready slot: the child
        }, "adopting-worker");
        try {
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(10));
            lock.lock();
            try {
                child.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!(lock.hasQueuedThread(child) && child.getState() == Thread.State.WAITING)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(lock.hasQueuedThread(child), "the child queues on the schedule's lock");

                watch.invoke(schedule);

                assertEquals(0, schedule.summary().escapes(),
                        "the arriving child waits for nothing but the schedule's own lock, which the"
                                + " watching worker holds; that is not an escape");
            } finally {
                lock.unlock();
            }
            child.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            schedule.close();
        }
    }
}
