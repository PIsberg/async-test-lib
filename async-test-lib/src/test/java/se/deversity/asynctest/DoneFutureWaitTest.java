package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.ControlledSchedule;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A woven {@code get} or {@code join} on a future that is already done does not wait in the
 * controlled schedule for the adopted task behind it (#1514). The task of a future cancelled while
 * queued, or completed by hand, may never run; waiting for it lasted the stall bound and counted an
 * escape, which tells the runner the seed no longer replays. Asserted through the schedule's own
 * escape count, not a clock.
 */
class DoneFutureWaitTest {

    private static final long WAIT_SECONDS = 10;

    /** Runs {@code body} as the only worker of a controlled round whose executor's thread is busy. */
    private static ControlledSchedule.Summary runWithABusyPool(Consumer<ExecutorService> body) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> { // unwoven, so not adopted: it only keeps the pool's one thread busy
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(WAIT_SECONDS, TimeUnit.SECONDS));
        ControlledSchedule schedule = ControlledSchedule.open(1, 3L);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            schedule.enter(0);
            try {
                body.accept(pool);
            } catch (Throwable t) { // NOPMD - reported to the test thread
                failed.set(t);
            } finally {
                schedule.leave();
            }
        }, "done-future-worker");
        try {
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertNull(failed.get(), () -> "the worker failed: " + failed.get());
            return schedule.summary();
        } finally {
            schedule.close();
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aGetOnAFutureCancelledWhileQueuedIsAPlainPoint() throws Exception {
        ControlledSchedule.Summary summary = runWithABusyPool(pool -> {
            Future<Object> queued = AgentConcurrencyUtilHooks.submit(pool, () -> 1);
            queued.cancel(false); // its task will never run
            try {
                AgentConcurrencyUtilHooks.get(queued);
                throw new AssertionError("a cancelled future's get must throw");
            } catch (CancellationException expected) {
                // what the call it replaced throws
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertEquals(0, summary.escapes(),
                "the future was done, so the get could not block; waiting for its task counted an escape: " + summary);
    }

    @Test
    void aJoinOnAFutureCompletedByHandIsAPlainPoint() throws Exception {
        ControlledSchedule.Summary summary = runWithABusyPool(pool -> {
            CompletableFuture<Object> queued = AgentConcurrencyUtilHooks.supplyAsync(() -> 1, pool);
            queued.complete(2); // done before its task, which is still queued, ever runs
            assertEquals(2, AgentConcurrencyUtilHooks.join(queued), "the join returns the value it was given");
        });
        assertEquals(0, summary.escapes(),
                "the future was done, so the join could not block; waiting for its task counted an escape: " + summary);
    }

    @Test
    void aTaskAbandonedToTheScheduleStillRunsIfItsExecutorRunsItAnyway() throws Exception {
        // The narrow case the compare-and-set exists for: a get found the future done and abandoned
        // the task, and the executor ran it all the same. It runs, outside the round, and its result
        // is the task's own; it does not join a slot the schedule already counts as done.
        AgentHandOffHooks.HandedTask handed = new AgentHandOffHooks.HandedTask((java.util.concurrent.Callable<Object>) () -> 42);
        handed.abandoned();
        handed.abandoned(); // a second abandon changes nothing
        assertEquals(42, handed.call(), "the abandoned task still does its work when it runs");
    }
}
