package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Calendar} mutators beyond {@code set} reach {@code CalendarDetector} through the
 * agent's hooks, each recorded the way its effect on the calendar's fields calls for (#820).
 *
 * <p>Only {@code get} and the {@code set} overloads were woven, so on the agent path an
 * {@code add}, {@code roll}, {@code clear}, {@code setTime}, {@code setTimeInMillis} or
 * {@code setTimeZone} on a shared calendar was never recorded. Which record method a hook calls
 * matters as much as calling one: after {@code setTime} every field is computed, so the next
 * {@code get} only reads, while after a {@code set} it recomputes fields, a write.
 */
class AgentCalendarMutationHooksTest {

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(false).detectCalendarIssues(true).build());
    }

    /** Starts the next round of {@code ctx} and runs each body on its own thread, released together. */
    private static void round(AsyncTestContext ctx, Runnable... bodies) throws InterruptedException {
        ctx.markInvocationStart();
        CyclicBarrier start = new CyclicBarrier(bodies.length);
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                AsyncTestContext.install(ctx);
                try {
                    start.await();
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "calendar-round-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static void underLock(ReentrantReadWriteLock lock, boolean shared, Runnable body) {
        java.util.concurrent.locks.Lock view = shared ? lock.readLock() : lock.writeLock();
        view.lock();
        HeldLocks.acquired(lock, shared);
        try {
            body.run();
        } finally {
            HeldLocks.released(lock, shared);
            view.unlock();
        }
    }

    private static boolean reported(AsyncTestContext ctx) {
        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.calendarDetector().analyze().hasIssues();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /**
     * The writer sets a field under the write lock, then, when {@code thenSetTime}, sets the time;
     * the next round reads the calendar from two threads under one read lock.
     */
    private static boolean getsUnderOneReadLockReported(boolean thenSetTime) throws InterruptedException {
        return getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.set(cal, Calendar.DAY_OF_MONTH, 3);
            if (thenSetTime) {
                AgentSharedInstanceHooks.setTime(cal, new Date(0L));
            }
        });
    }

    /** The writer runs {@code writes} under the write lock; the next round gets under one read lock. */
    private static boolean getsUnderOneReadLockReported(java.util.function.Consumer<Calendar> writes)
            throws InterruptedException {
        AsyncTestContext ctx = newContext();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        round(ctx, () -> underLock(lock, false, () -> writes.accept(cal)));
        Runnable get = () -> underLock(lock, true, () -> AgentSharedInstanceHooks.get(cal, Calendar.DAY_OF_MONTH));
        round(ctx, get, get);
        return reported(ctx);
    }

    @Test
    @DisplayName("a woven setTime leaves the next gets reads, so one read lock covers them")
    void aWovenSetTimeLeavesTheNextGetsReads() throws InterruptedException {
        assertFalse(getsUnderOneReadLockReported(true),
                "setTime computed every field the set left, under the write lock, so the gets under "
                        + "the read lock only read; the hook must record it as a setTime, not a set");
    }

    @Test
    @DisplayName("without the setTime, the same gets recompute the set's fields and are reported")
    void withoutTheSetTimeTheGetsWriteAndAreReported() throws InterruptedException {
        assertTrue(getsUnderOneReadLockReported(false),
                "after a set the next get recomputes fields into the calendar, a write that a "
                        + "shared read lock does not cover; the pair above is silent only because of "
                        + "the setTime");
    }

    @Test
    @DisplayName("each mutator hook performs the call it replaced")
    void eachMutatorHookPerformsItsCall() {
        Calendar viaHook = utc();
        Calendar viaJdk = utc();
        AgentSharedInstanceHooks.add(viaHook, Calendar.DAY_OF_MONTH, 40);
        viaJdk.add(Calendar.DAY_OF_MONTH, 40);
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "add");

        AgentSharedInstanceHooks.roll(viaHook, Calendar.MONTH, 3);
        viaJdk.roll(Calendar.MONTH, 3);
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "roll(int, int)");

        AgentSharedInstanceHooks.roll(viaHook, Calendar.HOUR_OF_DAY, true);
        viaJdk.roll(Calendar.HOUR_OF_DAY, true);
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "roll(int, boolean)");

        AgentSharedInstanceHooks.setTimeInMillis(viaHook, 86_400_000L);
        viaJdk.setTimeInMillis(86_400_000L);
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "setTimeInMillis");

        AgentSharedInstanceHooks.setTime(viaHook, new Date(172_800_000L));
        viaJdk.setTime(new Date(172_800_000L));
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "setTime");

        AgentSharedInstanceHooks.setTimeZone(viaHook, TimeZone.getTimeZone("Asia/Tokyo"));
        viaJdk.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        assertEquals(viaJdk.get(Calendar.HOUR_OF_DAY), viaHook.get(Calendar.HOUR_OF_DAY), "setTimeZone");

        AgentSharedInstanceHooks.clear(viaHook, Calendar.HOUR_OF_DAY);
        viaJdk.clear(Calendar.HOUR_OF_DAY);
        assertEquals(viaJdk.isSet(Calendar.HOUR_OF_DAY), viaHook.isSet(Calendar.HOUR_OF_DAY), "clear(int)");

        AgentSharedInstanceHooks.clear(viaHook);
        viaJdk.clear();
        assertEquals(viaJdk.getTimeInMillis(), viaHook.getTimeInMillis(), "clear()");
    }

    // ---- #820's narrow limits: a roll of an hour field, and a zero amount ----------------------
    //
    // Measured against GregorianCalendar's protected isTimeSet and areFieldsSet on JDK 26: a roll
    // of HOUR or HOUR_OF_DAY leaves both set, so the next get only reads; every other roll goes
    // through set() and leaves fields to recompute; an add or roll by 0 returns before touching
    // anything.

    @Test
    @DisplayName("a roll of an hour field computes every field, so the next gets only read")
    void aRollOfAnHourFieldLeavesTheNextGetsReads() throws InterruptedException {
        assertFalse(getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.set(cal, Calendar.DAY_OF_MONTH, 3);
            AgentSharedInstanceHooks.roll(cal, Calendar.HOUR_OF_DAY, 2);
        }), "roll(HOUR_OF_DAY) completes the set's fields and keeps them computed, as setTime does");
        assertFalse(getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.set(cal, Calendar.DAY_OF_MONTH, 3);
            AgentSharedInstanceHooks.roll(cal, Calendar.HOUR, true);
        }), "roll(HOUR, up) likewise");
    }

    @Test
    @DisplayName("a roll of any other field leaves fields to recompute, and the gets are reported")
    void aRollOfAnotherFieldStillLeavesTheGetsWrites() throws InterruptedException {
        assertTrue(getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.setTime(cal, new Date(0L));
            AgentSharedInstanceHooks.roll(cal, Calendar.MONTH, 2);
        }), "roll(MONTH) is a set() of MONTH, which leaves the other fields to recompute");
    }

    @Test
    @DisplayName("an add of zero changes nothing, so computed fields stay computed")
    void anAddOfZeroLeavesTheNextGetsReads() throws InterruptedException {
        assertFalse(getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.setTime(cal, new Date(0L));
            AgentSharedInstanceHooks.add(cal, Calendar.MONTH, 0);
            AgentSharedInstanceHooks.roll(cal, Calendar.YEAR, 0);
        }), "add and roll by 0 return before touching the calendar");
    }

    @Test
    @DisplayName("an add of a month leaves fields to recompute, and the gets are reported")
    void anAddOfAMonthLeavesTheGetsWrites() throws InterruptedException {
        assertTrue(getsUnderOneReadLockReported(cal -> {
            AgentSharedInstanceHooks.setTime(cal, new Date(0L));
            AgentSharedInstanceHooks.add(cal, Calendar.MONTH, 1);
        }), "add(MONTH, 1) sets MONTH and leaves the rest to recompute");
    }

    private static Calendar utc() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(0L);
        return calendar;
    }
}
