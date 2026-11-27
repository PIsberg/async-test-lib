package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grants around a rendezvous (#1021, #1495). Once every worker has stepped outside, the rendezvous
 * releases them all, and until the last is back no grant may be made: the order the OS wakes them in
 * would decide who runs next. Before that, while a worker still waits there for its peers, the round
 * must go on being handed out, since those peers need turns to reach it.
 *
 * <p>Driven through {@code stepOutside} and {@code rejoin}, the calls a rendezvous makes, with
 * latches standing in for the rendezvous itself so the test decides who comes back first.
 */
class ControlledScheduleRendezvousGrantTest {

    private static final long WAIT_SECONDS = 10;

    private static void awaitParked(Thread thread) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    /**
     * Runs a round of two workers that both step outside, the second out adopting a task first,
     * brings worker {@code firstBack} back, ends the task while that worker waits for its turn, then
     * brings the other back.
     *
     * @return the slot that ran first after the rendezvous
     */
    private static int firstToRunAfter(long seed, int firstBack, StringBuilder summary) throws Exception {
        ControlledSchedule schedule = ControlledSchedule.open(2, seed);
        AtomicInteger outs = new AtomicInteger();
        AtomicReference<Object> adoption = new AtomicReference<>();
        CountDownLatch bothOutside = new CountDownLatch(2);
        CountDownLatch[] back = {new CountDownLatch(1), new CountDownLatch(1)};
        List<Integer> order = new CopyOnWriteArrayList<>();
        Thread[] workers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int slot = i;
            workers[i] = new Thread(() -> {
                schedule.enter(slot);
                if (outs.getAndIncrement() == 1) {
                    adoption.set(ControlledSchedule.submitting());
                }
                boolean outside = ControlledSchedule.stepOutside();
                bothOutside.countDown();
                try {
                    back[slot].await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (outside) {
                    ControlledSchedule.rejoin();
                }
                order.add(slot);
                schedule.leave();
            }, "rendezvous-worker-" + i);
        }
        try {
            for (Thread worker : workers) {
                worker.start();
            }
            assertTrue(bothOutside.await(WAIT_SECONDS, TimeUnit.SECONDS), "the workers never reached the rendezvous");
            back[firstBack].countDown();
            awaitParked(workers[firstBack]);
            // A rejected task's taskAbandoned, on whatever thread the executor runs it: it marks the
            // slot done and, with nobody holding the round, grants it.
            ControlledSchedule.taskAbandoned(adoption.get());
            back[1 - firstBack].countDown();
            for (Thread worker : workers) {
                worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            }
            summary.append(schedule.summary());
            assertEquals(0, schedule.summary().escapes(), "nothing here escapes: " + schedule.summary());
            assertEquals(2, order.size(), "a worker never got the round back: " + schedule.summary());
            return order.get(0);
        } finally {
            for (CountDownLatch latch : back) {
                latch.countDown();
            }
            schedule.close();
        }
    }

    @Test
    void whoRunsAfterARendezvousDoesNotDependOnWhoIsBackFirst() throws Exception {
        // Before: taskDone() granted whenever nobody held the round, so the task ending between the
        // two returns gave the round to whichever worker was back already.
        for (long seed = 1; seed <= 8; seed++) {
            StringBuilder zeroFirst = new StringBuilder();
            StringBuilder oneFirst = new StringBuilder();
            int afterZeroFirst = firstToRunAfter(seed, 0, zeroFirst);
            int afterOneFirst = firstToRunAfter(seed, 1, oneFirst);
            assertEquals(afterZeroFirst, afterOneFirst, "seed " + seed
                    + ": the order the rendezvous woke the workers in decided who ran next; "
                    + zeroFirst + " / " + oneFirst);
        }
    }

    @Test
    void aWorkerBackFromAnEscapeGetsTheRoundWhileAPeerWaitsAtTheRendezvous() throws Exception {
        // The other half: a worker still waiting at the rendezvous must not hold up grants. Here the
        // first to run blocks and is counted escaped, its peer steps outside to wait for it, and
        // nobody is left to run. Withholding the grant when the escaped worker comes back left it
        // waiting for a turn and its peer waiting for it, until the round timed out.
        for (long seed = 1; seed <= 4; seed++) {
            ControlledSchedule schedule = ControlledSchedule.open(2, seed);
            AtomicBoolean claimed = new AtomicBoolean();
            CountDownLatch peerOutside = new CountDownLatch(1);
            CountDownLatch escapedArrived = new CountDownLatch(1);
            CountDownLatch gotTheRoundBack = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            Thread[] workers = new Thread[2];
            for (int i = 0; i < 2; i++) {
                int slot = i;
                workers[i] = new Thread(() -> {
                    try {
                        schedule.enter(slot);
                        if (claimed.compareAndSet(false, true)) {
                            // Blocked while holding the round: watch() counts it escaped.
                            while (!peerOutside.await(5, TimeUnit.MILLISECONDS)) {
                                Thread.onSpinWait();
                            }
                            ControlledSchedule.point(); // back from the escape
                            gotTheRoundBack.countDown();
                            boolean outside = ControlledSchedule.stepOutside();
                            escapedArrived.countDown();
                            if (outside) {
                                ControlledSchedule.rejoin();
                            }
                        } else {
                            boolean outside = ControlledSchedule.stepOutside();
                            peerOutside.countDown();
                            escapedArrived.await(WAIT_SECONDS, TimeUnit.SECONDS);
                            if (outside) {
                                ControlledSchedule.rejoin();
                            }
                        }
                        schedule.leave();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }, "worker-" + i);
            }
            try {
                for (Thread worker : workers) {
                    worker.start();
                }
                assertTrue(gotTheRoundBack.await(WAIT_SECONDS, TimeUnit.SECONDS), "seed " + seed
                        + ": the worker back from its escape never got the round while its peer waited"
                        + " at the rendezvous, " + schedule.summary());
                assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "seed " + seed + ": " + schedule.summary());
                assertEquals(1, schedule.summary().escapes(), "seed " + seed + ": the one block is the one escape, "
                        + schedule.summary());
            } finally {
                peerOutside.countDown();
                escapedArrived.countDown();
                schedule.close();
                for (Thread worker : workers) {
                    worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                }
            }
        }
    }
}
