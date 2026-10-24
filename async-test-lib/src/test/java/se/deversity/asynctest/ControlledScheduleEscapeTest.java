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
import se.deversity.asynctest.telemetry.ControlledSchedule;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the controlled schedule counts as an escape when the thread it handed the round to does not
 * take it, and what it does not (#1117).
 *
 * <p>The count is the runner's only evidence that a seed no longer replays exactly, so an escape
 * left uncounted passes every run and prints a replay line that promises too much, while a
 * non-escape counted as one makes a replayable seed look unreliable. Each case reads the
 * {@code runner.schedule.round ... escapes=N} line and expects one exact count. The bodies make the
 * woven calls by hand, as the other {@code ControlledSchedule*Test} classes do.
 */
class ControlledScheduleEscapeTest {

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

    private static AsyncTestConfig controlled(int threads) {
        return AsyncTestConfig.builder().threads(threads).invocations(1).replaySeed(1L)
                .controlledSchedule(true).timeoutMs(30_000).licenseMockMode(true).build();
    }

    private String theRound() {
        List<String> rounds = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("runner.schedule.round")).toList();
        assertEquals(1, rounds.size(), rounds.toString());
        return rounds.get(0);
    }

    /**
     * Releases {@code latch} once {@code worker} is parked in {@code hook}'s own blocking call, the
     * real {@code join} or {@code get} with no schedule frame left on its stack: the state it reaches
     * only after the schedule gave up on the thread it was waiting for. A timer would be a guess,
     * and under a loaded CI runner the run's own start can outlast it.
     */
    private static void releaseOnceParkedPastTheSchedule(Thread worker, Class<?> hook, CountDownLatch latch) {
        Thread releaser = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (latch.getCount() > 0 && System.nanoTime() < deadline) {
                StackTraceElement[] stack = worker.getStackTrace();
                boolean inHook = false;
                boolean inSchedule = false;
                for (StackTraceElement frame : stack) {
                    inHook |= frame.getClassName().equals(hook.getName());
                    inSchedule |= frame.getClassName().equals(ControlledSchedule.class.getName());
                }
                if (worker.getState() == Thread.State.WAITING && inHook && !inSchedule) {
                    break;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            latch.countDown();
        }, "escape-test-releaser");
        releaser.setDaemon(true);
        releaser.start();
    }

    /**
     * A task its executor cannot start, because the pool's one thread is busy, is handed the round
     * when the worker waits for it and never takes it: counted once the stall bound passes, so the
     * round goes on instead of waiting for the executor.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTaskItsExecutorDoesNotStartEscapes() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
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
            AtomicInteger ran = new AtomicInteger();

            AsyncTestRunner.run(controlled(1), () -> {
                releaseOnceParkedPastTheSchedule(Thread.currentThread(), AgentConcurrencyUtilHooks.class, release);
                Future<?> task = AgentConcurrencyUtilHooks.submit(pool, (Runnable) ran::incrementAndGet);
                AgentConcurrencyUtilHooks.get((Future<Object>) task);
            });

            assertEquals(1, ran.get(), "the task still runs once its executor frees up");
            String round = theRound();
            assertTrue(round.contains("escapes=1"),
                    "the round was handed to a task that never started, and nothing counted it: " + round);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * A child that runs to its end before reaching any point leaves the round done, not escaped:
     * while it ran every peer waited, so nothing a later grant reads depends on when it ended.
     */
    @Test
    void aChildThatEndsBeforeItsFirstPointIsDone_notAnEscape() throws Throwable {
        AtomicInteger ran = new AtomicInteger();

        AsyncTestRunner.run(controlled(1), () -> {
            Thread child = new Thread(ran::incrementAndGet, "ends-before-a-point");
            AgentThreadHooks.threadStart(child);
            AsyncTestContext.schedulePoint();
            AgentThreadHooks.threadJoin(child);
        });

        assertEquals(1, ran.get());
        String round = theRound();
        assertTrue(round.contains("escapes=0"), "a child's end is its leave, not an escape: " + round);
    }

    /**
     * A child granted the round that blocks before its first point, here on a latch released only once
     * the worker has stopped waiting for it inside the schedule, has stopped somewhere the schedule cannot see: an escape.
     */
    @Test
    void aChildThatBlocksBeforeItsFirstPointEscapes() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);

        AsyncTestRunner.run(controlled(1), () -> {
            releaseOnceParkedPastTheSchedule(Thread.currentThread(), AgentThreadHooks.class, release);
            Thread child = new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                AsyncTestContext.schedulePoint();
            }, "blocks-before-a-point");
            AgentThreadHooks.threadStart(child);
            AsyncTestContext.schedulePoint();
            AgentThreadHooks.threadJoin(child);
        });

        String round = theRound();
        assertTrue(round.contains("escapes=1"),
                "a child blocked outside the schedule held the round, and nothing counted it: " + round);
    }

    /**
     * A worker interrupted while it waits for its turn runs on uncontrolled, with its interrupt kept
     * for the body, and the round counts it: from there the order is the OS's, not the seed's.
     */
    @Test
    void aWorkerInterruptedWhileWaitingForItsTurnEscapes() throws Throwable {
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger sawInterrupt = new AtomicInteger();

        AsyncTestRunner.run(controlled(2), () -> {
            if (first.getAndSet(false)) {
                // Every worker has joined before the first grant, so the other one is waiting for
                // its turn now.
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (t != Thread.currentThread() && t.getName().startsWith("async-test-worker-")) {
                        t.interrupt();
                    }
                }
                // Keep the round, at no point and without parking: handed the turn now, the other
                // worker would run controlled with its interrupt kept, which is no escape. Spinning
                // leaves leaving its wait uncontrolled as its only way to reach the body.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (sawInterrupt.get() == 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                AsyncTestContext.schedulePoint();
            } else if (Thread.interrupted()) {
                sawInterrupt.incrementAndGet();
            }
        });

        assertEquals(1, sawInterrupt.get(), "the interrupt must reach the body it was meant for");
        String round = theRound();
        assertTrue(round.contains("escapes=1"), "the interrupted worker ran uncontrolled: " + round);
    }
}
