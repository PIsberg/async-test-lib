package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker interrupted while it waits for its turn, in the instant grant() hands it the round
 * (#1495). Its wait ends with the interrupt, so it runs on free and never takes the turn it was
 * just given. Left at that, the round stayed with it: watch() does not move a turn that was never
 * acknowledged, so its peer waited until the free worker's body ended, and for good when that body
 * waited for the peer.
 *
 * <p>The window is narrow and only one order of events opens it: the granting worker queued on
 * the schedule's lock first, the waiting worker's wait then cancelled by the interrupt and queued
 * behind it, the grant made, and only then the interrupted worker's turn at the lock. The test
 * makes that order by holding the schedule's own lock, read reflectively so production code keeps
 * no test seam, and finds the grant by replay: a seed's first run records at which of worker 0's
 * points the round first passes to worker 1 (it must within {@link ControlledSchedule#SPIN_LIMIT}
 * steps), and its second run stops worker 0 just before that point.
 */
class ControlledScheduleInterruptTest {

    private static final long WAIT_SECONDS = 3;
    private static final int POINTS = ControlledSchedule.SPIN_LIMIT + 200;
    /** How long to look for worker 1's wake between polls, which come every millisecond. */
    private static final long POLL_SYNC_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** What one run saw: where worker 1 first got the round, how long it took, whether it was aimed. */
    private record Run(int handedOverAt, long tookMs, boolean aimed, int fallbacks, String summary, String why) {
    }

    private static ReentrantLock lockOf(ControlledSchedule schedule) throws ReflectiveOperationException {
        Field field = ControlledSchedule.class.getDeclaredField("lock");
        field.setAccessible(true);
        return (ReentrantLock) field.get(schedule);
    }

    private static void awaitState(Thread thread, java.util.function.BooleanSupplier condition) {
        awaitState(thread, condition, TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
    }

    private static void awaitState(Thread thread, java.util.function.BooleanSupplier condition, long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /**
     * Runs {@code seed}; with {@code aimAt >= 0}, interrupts worker 1 in the window before point
     * {@code aimAt}. With {@code viaAcquire}, worker 1 waits at {@code acquiring} a lock, as a
     * {@code lockInterruptibly} does, and worker 0 asks for the same lock once its points are done.
     */
    private static Run run(long seed, int aimAt, boolean viaAcquire) throws Exception {
        ReentrantLock wanted = new ReentrantLock();
        ControlledSchedule schedule = ControlledSchedule.open(2, seed);
        ReentrantLock lock = lockOf(schedule);
        AtomicInteger firstPoints = new AtomicInteger();
        AtomicInteger handedOverAt = new AtomicInteger(-1);
        AtomicBoolean atAim = new AtomicBoolean();
        AtomicBoolean go = new AtomicBoolean();
        CountDownLatch peerWentOn = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread first = new Thread(() -> {
            schedule.enter(0);
            for (int i = 0; i < POINTS; i++) {
                if (i == aimAt) {
                    atAim.set(true);
                    while (!go.get()) {
                        Thread.onSpinWait(); // busy, not blocked: watch() must not count it escaped
                    }
                }
                ControlledSchedule.point();
                firstPoints.incrementAndGet();
            }
            if (viaAcquire) {
                ControlledSchedule.acquiring(wanted); // a point, and held up by any hold the table shows
            }
            peerWentOn.countDown();
            schedule.leave();
            done.countDown();
        }, "first-worker");
        Thread second = new Thread(() -> {
            schedule.enter(1);
            if (viaAcquire) {
                ControlledSchedule.acquiring(wanted);
                if (Thread.currentThread().isInterrupted()) {
                    // lockInterruptibly throws here; the hook gives back the hold it was granted
                    ControlledSchedule.acquireAbandoned(wanted);
                }
            } else {
                ControlledSchedule.point();
            }
            handedOverAt.set(firstPoints.get());
            Thread.interrupted(); // the test's interrupt, not the body's
            try {
                peerWentOn.await(WAIT_SECONDS, TimeUnit.SECONDS); // a body that waits for its peer
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            schedule.leave();
            done.countDown();
        }, "second-worker");
        long started = System.nanoTime();
        boolean aimed = false;
        // (reset once the aimed interrupt is in place, so only the schedule's own time is judged)
        String why = "";
        try {
            second.start();
            first.start();
            if (aimAt >= 0) {
                awaitState(first, atAim::get);
                // Worker 1 wakes every millisecond to watch, and a wake that finds the lock held
                // queues it too early. Start right after it parks again, with a whole poll to spare.
                // A wake is brief and a slow runner can miss it: then this attempt is spoilt, not
                // the run, and the poll check below sends it round again.
                awaitState(second, () -> second.getState() != Thread.State.TIMED_WAITING, POLL_SYNC_NANOS);
                awaitState(second, () -> second.getState() == Thread.State.TIMED_WAITING, POLL_SYNC_NANOS);
                lock.lock();
                try {
                    go.set(true);
                    awaitState(first, () -> lock.hasQueuedThread(first));
                    // Worker 1 must still be in its condition wait, not queued after a poll ran out:
                    // only a wait the interrupt cancels throws once the lock is free.
                    why = "first queued=" + lock.hasQueuedThread(first) + " second queued="
                            + lock.hasQueuedThread(second) + " second=" + second.getState();
                    if (lock.hasQueuedThread(first) && !lock.hasQueuedThread(second)
                            && second.getState() == Thread.State.TIMED_WAITING) {
                        second.interrupt();
                        awaitState(second, () -> lock.hasQueuedThread(second));
                        aimed = lock.hasQueuedThread(second);
                    }
                } finally {
                    lock.unlock();
                    // The schedule is on its own from here; the aiming above is the test's time.
                    started = System.nanoTime();
                }
            }
            done.await(WAIT_SECONDS * 3, TimeUnit.SECONDS);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Run(handedOverAt.get(), done.getCount() == 0 ? tookMs : Long.MAX_VALUE, aimed,
                    schedule.summary().fallbacks(), schedule.summary().toString(), why);
        } finally {
            go.set(true);
            schedule.close();
            first.join(2_000);
            second.join(2_000);
        }
    }

    /** The aimed runs of up to 64 seeds, at most {@code wanted} of them; each attempt retried up to ten times. */
    private static List<Run> aimedRuns(boolean viaAcquire, int wanted, List<String> notes) throws Exception {
        List<Run> runs = new ArrayList<>();
        for (long seed = 1; seed <= 64 && runs.size() < wanted; seed++) {
            Run dry = run(seed, -1, viaAcquire);
            if (dry.handedOverAt() <= 0 || dry.handedOverAt() >= POINTS) {
                notes.add(seed + ": dry handed over at " + dry.handedOverAt());
                continue; // worker 1 ran first on this seed: no grant to interrupt into
            }
            // Worker 1 reads how many points worker 0 had completed: the point that handed over is
            // the next one, with that index. Worker 1 polls every millisecond, and a poll that runs
            // out while the test holds the lock queues it before the interrupt can: not the window,
            // so try again.
            Run aimedRun = run(seed, dry.handedOverAt(), viaAcquire);
            for (int attempt = 1; attempt < 10 && !aimedRun.aimed(); attempt++) {
                aimedRun = run(seed, dry.handedOverAt(), viaAcquire);
            }
            if (aimedRun.aimed()) {
                runs.add(aimedRun);
            } else {
                notes.add(seed + ": not aimed, " + aimedRun.why());
            }
        }
        assertTrue(runs.size() >= 4, "only " + runs.size() + " seeds were interrupted in the window: the test aimed"
                + " at nothing. " + notes);
        return runs;
    }

    @Test
    void aWorkerInterruptedAsItIsGrantedTheRoundHandsItBack() throws Exception {
        List<String> stalled = new ArrayList<>();
        for (Run run : aimedRuns(false, 6, new ArrayList<>())) {
            if (run.tookMs() >= TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
                stalled.add((run.tookMs() == Long.MAX_VALUE ? "never ended" : run.tookMs() + " ms") + ": "
                        + run.summary());
            }
        }
        assertTrue(stalled.isEmpty(), "the round stayed with the interrupted worker, so its peer could not go on"
                + " until the interrupted worker gave up waiting for it: " + stalled);
    }

    @Test
    void aWorkerInterruptedAsItIsGrantedALockGivesTheHoldBack() throws Exception {
        // The grant recorded worker 1 holding the lock it was waiting to acquire. Interrupted, it runs
        // free, and a free worker's acquireAbandoned gives nothing back: the hold stayed in the
        // table, so worker 0's later acquire of the same lock waited on a holder that never held it
        // and the round went on only by a fallback grant.
        List<String> phantom = new ArrayList<>();
        for (Run run : aimedRuns(true, 6, new ArrayList<>())) {
            if (run.fallbacks() > 0 || run.tookMs() >= TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
                phantom.add(run.summary());
            }
        }
        assertTrue(phantom.isEmpty(), "a hold the interrupted worker never took held up its peer: " + phantom);
    }
}
