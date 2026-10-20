package se.deversity.asynctest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.diagnostics.WorkerSlot;
import se.deversity.asynctest.runner.ConcurrencyRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code controlledSchedule = true}: a round's workers run one at a time and change hands only at
 * scheduling points, in an order the round's replay seed decides, so a seed that produced an
 * interleaving replays it. Without the agent the only points are {@link AsyncTestContext#schedulePoint()};
 * {@code ControlledScheduleWeavingTest} in the agent module covers woven field accesses.
 */
class ControlledScheduleTest {

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

    private List<String> events(String prefix) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix)).toList();
    }

    private static AsyncTestConfig.Builder controlled(int threads, int invocations, long seed) {
        return AsyncTestConfig.builder().threads(threads).invocations(invocations).replaySeed(seed)
                .controlledSchedule(true).timeoutMs(60_000).licenseMockMode(true);
    }

    /** Which worker reached which of its four points, in the order they got there. */
    private static List<String> trace(long seed) throws Throwable {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        AsyncTestRunner.run(controlled(3, 1, seed).build(), () -> {
            int slot = WorkerSlot.current();
            for (int i = 0; i < 4; i++) {
                trace.add(slot + ":" + i);
                AsyncTestContext.schedulePoint();
            }
        });
        return List.copyOf(trace);
    }

    /** True when some worker's entries are not one contiguous run: the schedule switched mid-body. */
    private static boolean switchesMidBody(List<String> trace) {
        Set<String> finished = new HashSet<>();
        String current = null;
        for (String entry : trace) {
            String slot = entry.substring(0, entry.indexOf(':'));
            if (!slot.equals(current)) {
                if (current != null) {
                    finished.add(current);
                }
                if (finished.contains(slot)) {
                    return true;
                }
                current = slot;
            }
        }
        return false;
    }

    /** {@return the escapes the last controlled round counted, from its DEBUG event} */
    private int lastRoundEscapes() {
        List<String> rounds = events("runner.schedule.round");
        Matcher m = Pattern.compile("escapes=(\\d+)")
                .matcher(rounds.get(rounds.size() - 1));
        assertTrue(m.find(), "the round event carries escapes=: " + rounds);
        return Integer.parseInt(m.group(1));
    }

    @Test
    void aFixedSeedReplaysTheSameInterleaving() throws Throwable {
        // The promise is exact replay for a round no worker escaped; an escape (a worker that
        // blocked, or stalled past STALL_NANOS on a loaded machine) is counted precisely because
        // it voids it. So a pair of runs is compared whenever both were escape-free, and an
        // escaped one is set aside rather than read as a broken replay (#1063). A real
        // nondeterminism with no escape still fails here.
        int compared = 0;
        List<String> setAside = new ArrayList<>();
        for (long seed = 1; seed <= 5; seed++) {
            List<String> first = trace(seed);
            boolean firstExact = lastRoundEscapes() == 0;
            assertEquals(12, first.size(), first.toString());
            for (int run = 0; run < 4; run++) {
                List<String> again = trace(seed);
                if (firstExact && lastRoundEscapes() == 0) {
                    assertEquals(first, again, "seed " + seed + " must replay its interleaving, and neither"
                            + " run escaped: " + events("runner.schedule.round"));
                    compared++;
                } else {
                    setAside.add("seed " + seed + " run " + run);
                }
            }
        }
        assertTrue(compared >= 16, "too few escape-free pairs to test replay (" + compared + " of 20); set"
                + " aside: " + setAside + "; rounds: " + events("runner.schedule.round"));
    }

    @Test
    void differentSeedsInterleaveDifferently_andSomeSwitchInsideABody() throws Throwable {
        Set<List<String>> seen = new HashSet<>();
        boolean switched = false;
        for (long seed = 1; seed <= 32; seed++) {
            List<String> trace = trace(seed);
            seen.add(trace);
            switched |= switchesMidBody(trace);
        }
        assertTrue(seen.size() > 3, "32 seeds produced only " + seen.size() + " interleavings: " + seen);
        assertTrue(switched, "no seed switched workers between two points of one body: " + seen);
    }

    @Test
    void onlyOneWorkerRunsBetweenTwoPoints() throws Throwable {
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        AsyncTestRunner.run(controlled(4, 5, 0L).build(), () -> {
            for (int i = 0; i < 5; i++) {
                if (inside.incrementAndGet() != 1) {
                    overlapped.set(true);
                }
                for (int spin = 0; spin < 2_000; spin++) {
                    Thread.onSpinWait();
                }
                inside.decrementAndGet();
                AsyncTestContext.schedulePoint();
            }
        });
        assertFalse(overlapped.get(), "two workers ran between the same pair of points");
    }

    /** A counter whose increment reads, reaches a point, then writes: an update lost to a switch. */
    static final class ReadPointWriteCounter {
        int value;

        void increment() {
            int read = value;
            AsyncTestContext.schedulePoint();
            value = read + 1;
        }
    }

    private static int finalCount(long seed, boolean virtualThreads) throws Throwable {
        ReadPointWriteCounter counter = new ReadPointWriteCounter();
        AsyncTestRunner.run(controlled(2, 1, seed).useVirtualThreads(virtualThreads).build(),
                counter::increment);
        return counter.value;
    }

    @Test
    void aLostUpdateFoundOnceIsReplayedEveryTime() throws Throwable {
        for (boolean virtualThreads : new boolean[] {true, false}) {
            long losing = 0L;
            long keeping = 0L;
            for (long seed = 1; seed <= 64 && (losing == 0L || keeping == 0L); seed++) {
                int count = finalCount(seed, virtualThreads);
                if (count == 1 && losing == 0L) {
                    losing = seed;
                } else if (count == 2 && keeping == 0L) {
                    keeping = seed;
                }
            }
            assertTrue(losing != 0L, "no seed in 1..64 lost an update (virtual=" + virtualThreads + ")");
            assertTrue(keeping != 0L, "every seed in 1..64 lost an update (virtual=" + virtualThreads + ")");
            for (int run = 0; run < 10; run++) {
                assertEquals(1, finalCount(losing, virtualThreads),
                        "seed " + losing + " lost the update once and must lose it on every replay");
                assertEquals(2, finalCount(keeping, virtualThreads),
                        "seed " + keeping + " kept both updates once and must keep them on every replay");
            }
        }
    }

    @Test
    void aWorkerThatBlocksOutsideAPointDoesNotHangTheRound_andTheRoundSaysSo() throws Throwable {
        Object lock = new Object();
        for (long seed = 1; seed <= 20; seed++) {
            AsyncTestRunner.run(controlled(3, 1, seed).timeoutMs(20_000).build(), () -> {
                synchronized (lock) {
                    AsyncTestContext.schedulePoint();
                }
            });
        }
        List<String> rounds = events("runner.schedule.round");
        assertEquals(20, rounds.size(), "one event per controlled round: " + rounds);
        assertTrue(rounds.stream().allMatch(m -> m.contains("escapes=")), rounds.toString());
        assertTrue(rounds.stream().anyMatch(m -> !m.contains("escapes=0")),
                "a worker blocked on a lock its paused peer holds left the schedule, and no round said so: "
                        + rounds);
    }

    @Test
    void aRunThatReachesNoPointSaysItInterleavedNothing() throws Throwable {
        AsyncTestRunner.run(controlled(2, 2, 3L).build(), () -> { });
        List<String> unpointed = events("runner.schedule.unpointed");
        assertEquals(1, unpointed.size(), "once per run, not per round: " + unpointed);
        assertTrue(unpointed.get(0).contains("hint="), unpointed.get(0));
    }

    @Test
    void aPointOutsideAControlledRunDoesNothing() throws Throwable {
        AsyncTestContext.schedulePoint();
        AtomicInteger bodies = new AtomicInteger();
        AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(2).timeoutMs(10_000)
                .licenseMockMode(true).build(), () -> {
            AsyncTestContext.schedulePoint();
            bodies.incrementAndGet();
        });
        assertEquals(4, bodies.get());
        assertTrue(events("runner.schedule.round").isEmpty(), "an uncontrolled run has no schedule");
    }
}
