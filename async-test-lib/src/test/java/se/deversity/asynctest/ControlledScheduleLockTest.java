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
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A controlled schedule knows which worker holds which lock (#1022).
 *
 * <p>A worker paused at a point inside {@code synchronized} still holds the monitor. The schedule
 * used to hand the round to a peer whose next step was entering that monitor; the peer blocked, the
 * waiting workers marked it escaped, and the round lost its exact replay. Every body that contends
 * on a lock paid an escape.
 *
 * <p>These bodies make the calls the agent weaves around a lock, by hand:
 * {@code TelemetryRegistry.monitorEntered} just before entering a {@code synchronized} block and
 * {@code monitorExited} just before leaving it, and {@code AgentLockHooks.lock}/{@code unlock} in
 * place of {@code Lock.lock()}/{@code unlock()}. The agent module's
 * {@code ControlledScheduleLockWeavingTest} runs the same shapes through the real weaver.
 */
class ControlledScheduleLockTest {

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

    private static AsyncTestConfig.Builder controlled(int threads, long seed) {
        return AsyncTestConfig.builder().threads(threads).invocations(1).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true);
    }

    private void assertNoRoundEscaped(int expectedRounds) {
        List<String> rounds = rounds();
        assertEquals(expectedRounds, rounds.size(), "one event per controlled round: " + rounds);
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")),
                "a worker paused while holding a lock its peers need must not cost an escape: the "
                        + "schedule should run someone who can proceed instead. Rounds: " + rounds);
        assertTrue(rounds.stream().anyMatch(m -> !m.contains("switches=0")),
                "no round ever changed hands, so the test exercised nothing: " + rounds);
    }

    @Test
    void aWorkerPausedInsideASynchronizedBlockCostsNoEscape() throws Throwable {
        Object lock = new Object();
        for (long seed = 1; seed <= 20; seed++) {
            AtomicInteger inside = new AtomicInteger();
            int[] count = new int[1];
            AsyncTestRunner.run(controlled(3, seed).build(), () -> {
                AsyncTestContext.schedulePoint();
                TelemetryRegistry.monitorEntered(lock);
                synchronized (lock) {
                    assertEquals(1, inside.incrementAndGet(), "two workers inside one monitor");
                    int seen = count[0];
                    AsyncTestContext.schedulePoint();   // paused here, still holding the monitor
                    count[0] = seen + 1;
                    inside.decrementAndGet();
                    TelemetryRegistry.monitorExited(lock);
                }
            });
            assertEquals(3, count[0], "seed " + seed + ": the monitor guards the count");
        }
        assertNoRoundEscaped(20);
    }

    @Test
    void aWorkerPausedHoldingAReentrantLockCostsNoEscape() throws Throwable {
        ReentrantLock lock = new ReentrantLock();
        for (long seed = 1; seed <= 20; seed++) {
            int[] count = new int[1];
            AsyncTestRunner.run(controlled(3, seed).build(), () -> {
                AsyncTestContext.schedulePoint();
                AgentLockHooks.lock(lock);
                try {
                    int seen = count[0];
                    AsyncTestContext.schedulePoint();   // paused here, still holding the lock
                    count[0] = seen + 1;
                } finally {
                    AgentLockHooks.unlock(lock);
                }
            });
            assertEquals(3, count[0], "seed " + seed + ": the lock guards the count");
        }
        assertNoRoundEscaped(20);
    }

    @Test
    void aReentrantAcquisitionOfAHeldMonitorIsNotWaitedOn() throws Throwable {
        Object lock = new Object();
        for (long seed = 1; seed <= 10; seed++) {
            AsyncTestRunner.run(controlled(2, seed).build(), () -> {
                TelemetryRegistry.monitorEntered(lock);
                synchronized (lock) {
                    AsyncTestContext.schedulePoint();
                    TelemetryRegistry.monitorEntered(lock);
                    synchronized (lock) {           // the holder enters again: never blocks
                        AsyncTestContext.schedulePoint();
                        TelemetryRegistry.monitorExited(lock);
                    }
                    TelemetryRegistry.monitorExited(lock);
                }
            });
        }
        List<String> rounds = rounds();
        assertEquals(10, rounds.size(), rounds.toString());
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=0")), rounds.toString());
    }

    @Test
    void aRealDeadlockStillDeadlocks_andTheRoundEndsInsteadOfHanging() {
        // Every ready worker waits on a lock another holds. The round must end (the runner's round
        // timeout) with the workers deadlocked on each other's monitors, where ThreadMXBean and
        // DeadlockDetector can see the cycle, never with the scheduler waiting forever. This guards
        // against a hang; it does not tell the grant fallback apart from its absence, because the
        // round timeout interrupts withheld workers into the same deadlock. The wait/notify test
        // below is the one the fallback decides.
        Object first = new Object();
        Object second = new Object();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        AtomicBoolean sawWorkersDeadlocked = new AtomicBoolean();
        AtomicBoolean watching = new AtomicBoolean(true);
        Thread watcher = new Thread(() -> {
            while (watching.get() && !sawWorkersDeadlocked.get()) {
                long[] ids = threads.findDeadlockedThreads();
                if (ids != null) {
                    for (ThreadInfo info : threads.getThreadInfo(ids)) {
                        if (info != null && info.getThreadName().startsWith("async-test-worker")) {
                            sawWorkersDeadlocked.set(true);
                        }
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "deadlock-watcher");
        watcher.setDaemon(true);
        watcher.start();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (long seed = 1; seed <= 6; seed++) {
                AtomicInteger slot = new AtomicInteger();
                try {
                    AsyncTestRunner.run(controlled(2, seed).timeoutMs(2_000).build(), () -> {
                        boolean forward = slot.getAndIncrement() % 2 == 0;
                        Object a = forward ? first : second;
                        Object b = forward ? second : first;
                        TelemetryRegistry.monitorEntered(a);
                        synchronized (a) {
                            AsyncTestContext.schedulePoint();
                            TelemetryRegistry.monitorEntered(b);
                            synchronized (b) {
                                TelemetryRegistry.monitorExited(b);
                            }
                            TelemetryRegistry.monitorExited(a);
                        }
                    });
                } catch (Throwable expected) { // NOPMD - a deadlocked round fails; finishing is the point
                    // the round timed out or reported the deadlock
                }
            }
        });
        watching.set(false);
        assertTrue(sawWorkersDeadlocked.get(),
                "no seed in 1..6 left the workers deadlocked on each other's monitors, so the "
                        + "deadlock in the code under test never happened");
    }

    @Test
    void aWaitInsideAHeldMonitorDoesNotStallTheRound() throws Throwable {
        // Object.wait releases the monitor. Since #1048 the woven wait tells the table so, but a wait
        // the agent did not weave still leaves the waiter listed as the holder. With the waiter
        // blocked and the notifier the only ready worker, waiting on that stale hold, the round must
        // still go to the notifier, or correct wait/notify code would end in a round timeout.
        for (long seed = 1; seed <= 10; seed++) {
            Object monitor = new Object();
            boolean[] ready = new boolean[1];
            AtomicInteger slot = new AtomicInteger();
            AsyncTestRunner.run(controlled(2, seed).timeoutMs(10_000).build(), () -> {
                boolean waiter = slot.getAndIncrement() == 0;
                TelemetryRegistry.monitorEntered(monitor);
                synchronized (monitor) {
                    if (waiter) {
                        while (!ready[0]) {
                            AgentMonitorHooks.monitorWait(monitor);
                        }
                    } else {
                        ready[0] = true;
                        AgentMonitorHooks.monitorNotifyAll(monitor);
                    }
                    TelemetryRegistry.monitorExited(monitor);
                }
            });
        }
    }

    private void assertNoRoundFellBack(int expectedRounds) {
        List<String> rounds = rounds();
        assertEquals(expectedRounds, rounds.size(), "one event per controlled round: " + rounds);
        assertTrue(rounds.stream().allMatch(m -> m.contains("fallbacks=0")),
                "a round went to a worker waiting on a lock nobody really held, because the schedule"
                        + " still listed a holder that had let it go (#1048). Rounds: " + rounds);
    }

    @Test
    void aWovenWaitLetsTheMonitorGo() throws Throwable {
        // The waiter blocks in wait() and escapes; the notifier then waits at its monitorEntered
        // point. The monitor is free, so the notifier can proceed and the grant is an ordinary one,
        // not the everyone-is-waiting fallback a stale hold by the waiter forced.
        int waits = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Object monitor = new Object();
            boolean[] ready = new boolean[1];
            AtomicInteger waited = new AtomicInteger();
            AtomicInteger slot = new AtomicInteger();
            AsyncTestRunner.run(controlled(2, seed).timeoutMs(10_000).build(), () -> {
                boolean waiter = slot.getAndIncrement() == 0;
                AsyncTestContext.schedulePoint();
                TelemetryRegistry.monitorEntered(monitor);
                synchronized (monitor) {
                    if (waiter) {
                        while (!ready[0]) {
                            waited.incrementAndGet();
                            AgentMonitorHooks.monitorWait(monitor);
                        }
                    } else {
                        ready[0] = true;
                        AgentMonitorHooks.monitorNotifyAll(monitor);
                    }
                    TelemetryRegistry.monitorExited(monitor);
                }
            });
            waits += waited.get();
        }
        assertTrue(waits > 0, "no seed made the waiter wait, so nothing was exercised");
        assertNoRoundFellBack(20);
    }

    /** A synchronized method that throws: no return instruction, so no woven monitor exit. */
    static final class Throwing {
        synchronized void boom() {
            TelemetryRegistry.methodMonitorEntered(this);
            throw new IllegalStateException("leaves the monitor by an exception");
        }
    }

    @Test
    void aMonitorLeftByAnExceptionIsNotHeldForTheSchedule() throws Throwable {
        // The method's exit is never woven, so the table keeps the hold. Each worker re-confirms its
        // own holds at its next point, on its own thread, and the peer waiting to enter the same
        // monitor gets an ordinary grant instead of the fallback.
        for (long seed = 1; seed <= 20; seed++) {
            Throwing throwing = new Throwing();
            int[] count = new int[1];
            AsyncTestRunner.run(controlled(2, seed).build(), () -> {
                AsyncTestContext.schedulePoint();
                try {
                    throwing.boom();
                } catch (IllegalStateException expected) { // NOPMD - the throw is the scenario
                    // the monitor is released by the exception, unseen by the table
                }
                AsyncTestContext.schedulePoint();
                TelemetryRegistry.monitorEntered(throwing);
                synchronized (throwing) {
                    count[0]++;
                    TelemetryRegistry.monitorExited(throwing);
                }
            });
            assertEquals(2, count[0], "seed " + seed);
        }
        assertNoRoundFellBack(20);
    }
}
