package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.runner.ConcurrencyRunner;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task the body hands to an executor joins the controlled schedule (#1049): it is adopted at the
 * woven {@code submit}, joins the round when it starts on the pool thread and leaves when it ends,
 * and a {@code get} on its future waits inside the schedule. So a worker racing a task it submitted
 * is split and replayed like two workers, with no escape.
 *
 * <p>The bodies make the woven calls by hand: {@code AgentConcurrencyUtilHooks.submit} and
 * {@code get} in place of {@code ExecutorService.submit} and {@code Future.get}. The pool is one of
 * the JDK's own, which the agent wraps tasks for.
 */
class ControlledScheduleTaskTest {

    private ch.qos.logback.classic.Logger runnerLog;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void captureRunnerLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        runnerLog = context.getLogger(ConcurrencyRunner.class);
        previousLevel = runnerLog.getLevel();
        runnerLog.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        runnerLog.addAppender(appender);
    }

    @AfterEach
    void restore() {
        runnerLog.detachAppender(appender);
        appender.stop();
        runnerLog.setLevel(previousLevel);
    }

    private List<String> rounds() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
    }

    /** An increment that reads, reaches a scheduling point, then writes: an update lost to a switch. */
    static final class ReadPointWriteCounter {
        int value;

        void increment() {
            int read = value;
            AsyncTestContext.schedulePoint();
            value = read + 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static int finalCount(long seed, ExecutorService pool) throws Throwable {
        ReadPointWriteCounter counter = new ReadPointWriteCounter();
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true).build(), () -> {
            Future<?> task = AgentConcurrencyUtilHooks.submit(pool, (Runnable) counter::increment);
            counter.increment();
            AgentConcurrencyUtilHooks.get((Future<Object>) task);
        });
        return counter.value;
    }

    @Test
    void aWorkerRacingATaskItSubmittedIsSplitBySomeSeed_andThatSeedReplays() throws Throwable {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long losing = 0L;
            long keeping = 0L;
            for (long seed = 1; seed <= 64 && (losing == 0L || keeping == 0L); seed++) {
                int count = finalCount(seed, pool);
                if (count == 1 && losing == 0L) {
                    losing = seed;
                } else if (count == 2 && keeping == 0L) {
                    keeping = seed;
                }
            }
            assertTrue(losing != 0L, "no seed in 1..64 split the worker's increment and its task's: " + rounds());
            assertTrue(keeping != 0L, "every seed in 1..64 lost the update: " + rounds());
            appender.list.clear();
            for (int run = 0; run < 10; run++) {
                assertEquals(1, finalCount(losing, pool), "seed " + losing + " must lose the update on every replay");
                assertEquals(2, finalCount(keeping, pool), "seed " + keeping + " must keep both on every replay");
            }
            List<String> rounds = rounds();
            assertEquals(20, rounds.size(), rounds.toString());
            assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                    "the task ran outside the schedule, or the get blocked outside it: " + rounds);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPoolThreadCarriesNothingOutOfTheRound() throws Throwable {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            finalCount(3L, pool);
            // The same pool thread, now outside any run: a point there must return at once, which
            // it does only if the task's binding to the round ended with the task.
            Future<?> after = pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    AsyncTestContext.schedulePoint();
                }
            });
            after.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A one-thread pool with no queue, its thread held busy until {@code release} counts down, so
     * every submission inside the round is refused and goes to {@code onRejection}.
     */
    private static ThreadPoolExecutor saturatedPool(RejectedExecutionHandler onRejection,
                                                    CountDownLatch release) throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), onRejection);
        CountDownLatch busy = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        busy.await();
        return pool;
    }

    private void assertEveryRoundCleanAndCounted(int expected) {
        List<String> rounds = rounds();
        assertEquals(expected, rounds.size(), rounds.toString());
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "an adopted task that never joined left its slot waiting until the stall bound: " + rounds);
    }

    /**
     * Under a caller-runs policy the adopted task runs inline on the submitting worker, inside its
     * turn, and never joins on a thread of its own; its slot must be done at once. Left ready, the
     * schedule waits for a task that will never arrive until the stall bound counts it an escape.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTaskTheCallerRunsInlineIsDoneAtOnce() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = saturatedPool(new ThreadPoolExecutor.CallerRunsPolicy(), release);
        try {
            for (long seed = 1; seed <= 4; seed++) {
                ReadPointWriteCounter counter = new ReadPointWriteCounter();
                AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                        .controlledSchedule(true).timeoutMs(10_000).licenseMockMode(true).build(), () -> {
                    Future<?> task = AgentConcurrencyUtilHooks.submit(pool, (Runnable) counter::increment);
                    counter.increment();
                    AgentConcurrencyUtilHooks.get((Future<Object>) task);
                });
                assertEquals(2, counter.value, "an inline task runs in the worker's turn, so nothing interleaves it");
            }
            assertEveryRoundCleanAndCounted(4);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** A task its executor rejects never runs, so its adopted slot must be done before the round goes on. */
    @Test
    void aTaskTheExecutorRejectsIsDoneAtOnce() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = saturatedPool(new ThreadPoolExecutor.AbortPolicy(), release);
        try {
            for (long seed = 1; seed <= 4; seed++) {
                AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                        .controlledSchedule(true).timeoutMs(10_000).licenseMockMode(true).build(), () -> {
                    assertThrows(RejectedExecutionException.class,
                            () -> AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> { }));
                    for (int i = 0; i < 3; i++) {
                        AsyncTestContext.schedulePoint();
                    }
                });
            }
            assertEveryRoundCleanAndCounted(4);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
