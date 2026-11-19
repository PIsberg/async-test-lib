package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentLockHooks;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interrupted {@code lockInterruptibly} gives back only a hold the schedule took for it
 * (#1418). A worker interrupted while it waited for its turn at the point before the acquire was
 * freed, never granted, and so never given the hold; giving one back anyway took away a hold it
 * still had, leaving the schedule's table saying a lock it holds is free.
 */
class ControlledScheduleAbandonedAcquireTest {

    @Test
    void anAcquireInterruptedBeforeItsTurnGivesBackNothing() throws Exception {
        Field ownersField = ControlledSchedule.class.getDeclaredField("owners");
        ownersField.setAccessible(true);
        int checked = 0;
        for (long seed = 1; seed <= 32; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(2, seed);
            ReentrantLock lock = new ReentrantLock();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger holdsAfter = new AtomicInteger(-1);
            AtomicBoolean waitedForATurn = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(2);
            Thread holder = new Thread(() -> {
                try {
                    schedule.enter(0);
                    AgentLockHooks.lock(lock);
                    Thread.currentThread().interrupt();
                    try {
                        AgentLockHooks.lockInterruptibly(lock);
                        AgentLockHooks.unlock(lock);
                    } catch (InterruptedException expected) {
                        // the acquire was abandoned
                    }
                    waitedForATurn.set(schedule.summary().escapes() > 0);
                    @SuppressWarnings("unchecked")
                    Map<Object, int[]> owners = (Map<Object, int[]>) ownersField.get(schedule);
                    int[] owner = owners.get(lock);
                    holdsAfter.set(owner == null ? 0 : owner[1]);
                    AgentLockHooks.unlock(lock);
                    stop.set(true);
                    schedule.leave();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            }, "holder");
            Thread other = new Thread(() -> {
                schedule.enter(1);
                for (int i = 0; i < 200 && !stop.get(); i++) {
                    ControlledSchedule.point();
                }
                schedule.leave();
                done.countDown();
            }, "other");
            try {
                holder.start();
                other.start();
                assertTrue(done.await(20, TimeUnit.SECONDS), "seed " + seed + ": a worker hung");
                if (waitedForATurn.get()) {
                    checked++;
                    assertEquals(1, holdsAfter.get(), "seed " + seed + ": the worker still holds the"
                            + " lock once, and the schedule must say so");
                }
            } finally {
                schedule.close();
            }
        }
        assertTrue(checked > 0, "no seed in 1..32 interrupted the worker while it waited for its turn");
    }
}
