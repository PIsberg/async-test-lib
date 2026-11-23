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
     *
     * {@return set once the worker was seen parked there} The release after the 20-second deadline is
     * only a way out: a test must assert this, or a worker the schedule never let go passes too.
     * Both tests below did for as long as a waiter stayed held for an escaped thread or task: each
     * ran 20 seconds and passed (#1495).
     */
    private static AtomicBoolean releaseOnceParkedPastTheSchedule(Thread worker, Class<?> hook,
                                                                  CountDownLatch latch) {
        AtomicBoolean parked = new AtomicBoolean();
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
                    parked.set(true);
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
        return parked;
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
            AtomicBoolean parked = new AtomicBoolean();

            AsyncTestRunner.run(controlled(1), () -> {
                parked.set(false);
                AtomicBoolean seen = releaseOnceParkedPastTheSchedule(Thread.currentThread(),
                        AgentConcurrencyUtilHooks.class, release);
                Future<?> task = AgentConcurrencyUtilHooks.submit(pool, (Runnable) ran::incrementAndGet);
                AgentConcurrencyUtilHooks.get((Future<Object>) task);
                parked.set(seen.get());
            });

            assertTrue(parked.get(), "the worker never reached the real get: the schedule held it for a"
                    + " task that escaped, until the releaser's deadline let the task run");
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
        AtomicBoolean parked = new AtomicBoolean();

        AsyncTestRunner.run(controlled(1), () -> {
            AtomicBoolean seen = releaseOnceParkedPastTheSchedule(Thread.currentThread(), AgentThreadHooks.class,
                    release);
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
            parked.set(seen.get());
        });

        assertTrue(parked.get(), "the worker never reached the real join: the schedule held it for a"
                + " child that escaped, until the releaser's deadline let the child end");
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

    /**
     * A timed get keeps its timeout under a controlled schedule. It used to wait inside the schedule
     * until the task was done, as an untimed get does, so on every seed a get that should have timed
     * out returned the value: the schedule forced one branch of the code under test and hid the other
     * (#1495). As a plain point, a seed may still run the task first (a worker descheduled for that
     * long is real too), but some seed must reach the timeout.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTimedGetOnATaskStillRunningCanTimeOut() throws Throwable {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            java.util.Set<String> outcomes = java.util.concurrent.ConcurrentHashMap.newKeySet();
            for (long seed = 1; seed <= 8 && !outcomes.contains("timed out"); seed++) {
                AsyncTestRunner.run(AsyncTestConfig.builder().threads(1).invocations(1).replaySeed(seed)
                        .controlledSchedule(true).timeoutMs(30_000).licenseMockMode(true).build(), () -> {
                    Future<?> task = AgentConcurrencyUtilHooks.submit(pool, (Runnable) () -> {
                        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                        while (System.nanoTime() < end) {
                            Thread.onSpinWait(); // no scheduling point: busy, not blocked
                        }
                    });
                    try {
                        AgentConcurrencyUtilHooks.get((Future<Object>) task, 20, TimeUnit.MILLISECONDS);
                        outcomes.add("returned");
                    } catch (java.util.concurrent.TimeoutException expected) {
                        outcomes.add("timed out");
                    }
                    AgentConcurrencyUtilHooks.get((Future<Object>) task);
                });
            }

            assertTrue(outcomes.contains("timed out"),
                    "no seed in 1..8 let a 20 ms get on a task that runs 300 ms time out: " + outcomes);
        } finally {
            pool.shutdownNow();
        }
    }
}
