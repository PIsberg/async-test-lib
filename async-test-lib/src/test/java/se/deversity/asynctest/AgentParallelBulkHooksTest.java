package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A parallel bulk operation outside the Stream API, a {@code ConcurrentHashMap.forEach} with a
 * parallelism threshold or an {@code Arrays.parallelSetAll}, carries the run to the common-pool
 * threads that help it, as a parallel stream's {@code forEach} does (#1156). Before, the helpers
 * ran the action unattributed, so a tally they all bumped with no lock drew no finding.
 */
class AgentParallelBulkHooksTest {

    /** Enough elements, each slow enough, that the common pool's workers join in. */
    private static final int ELEMENTS = 256;

    @BeforeEach
    void drainTelemetry() {
        TelemetryRegistry.start();
    }

    @AfterEach
    void leaveTheRun() {
        AsyncTestContext.uninstall();
        TelemetryRegistry.stop();
    }

    private static void installRun() {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** With no helper to observe this machine proves nothing, and a pass would read as coverage. */
    private static boolean commonPoolCanHelp() {
        return ForkJoinPool.commonPool().getParallelism() > 1;
    }

    private static ConcurrentHashMap<Object, Object> filledMap() {
        ConcurrentHashMap<Object, Object> map = new ConcurrentHashMap<>();
        for (int i = 0; i < ELEMENTS; i++) {
            map.put(i, i);
        }
        return map;
    }

    /** Notes a helper thread, and whether it ran with no run lent to it. */
    private static void checkLent(Thread caller, Set<Thread> helpers, AtomicBoolean outside) {
        pause();
        if (Thread.currentThread() != caller) {
            helpers.add(Thread.currentThread());
            if (AsyncTestContext.agentContext() == null) {
                outside.set(true);
            }
        }
    }

    private static void assertHelpedInsideTheRun(Set<Thread> helpers, AtomicBoolean outside) {
        assertFalse(outside.get(), "a helper ran the action with no run lent to it");
        if (commonPoolCanHelp()) {
            assertFalse(helpers.isEmpty(), "the common pool should have helped, or this test observed nothing");
        }
    }

    @Test
    @DisplayName("a helper works for the run while it runs a ConcurrentHashMap.forEach action")
    void aMapForEachHelperWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        AgentConcurrencyUtilHooks.concurrentMapForEach(filledMap(), 1,
                (key, value) -> checkLent(caller, helpers, outside));

        assertHelpedInsideTheRun(helpers, outside);
    }

    @Test
    @DisplayName("forEachKey, forEachValue and forEachEntry lend the run as forEach does")
    void theKeyValueAndEntryFormsLendTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();
        LongAdder seen = new LongAdder();

        AgentConcurrencyUtilHooks.concurrentMapForEachKey(filledMap(), 1, key -> {
            seen.increment();
            checkLent(caller, helpers, outside);
        });
        AgentConcurrencyUtilHooks.concurrentMapForEachValue(filledMap(), 1, value -> {
            seen.increment();
            checkLent(caller, helpers, outside);
        });
        AgentConcurrencyUtilHooks.concurrentMapForEachEntry(filledMap(), 1, entry -> {
            seen.increment();
            checkLent(caller, helpers, outside);
        });

        assertEquals(3L * ELEMENTS, seen.sum(), "every entry runs exactly once through each form");
        assertHelpedInsideTheRun(helpers, outside);
    }

    @Test
    @DisplayName("the transforming forms lend the run to the transformer and to the action")
    void theTransformingFormsLendTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();
        LongAdder transformed = new LongAdder();
        LongAdder acted = new LongAdder();

        AgentConcurrencyUtilHooks.concurrentMapForEach(filledMap(), 1, (key, value) -> {
            transformed.increment();
            checkLent(caller, helpers, outside);
            return (Integer) key % 2 == 0 ? key : null; // null skips the action
        }, key -> {
            acted.increment();
            checkLent(caller, helpers, outside);
        });
        AgentConcurrencyUtilHooks.concurrentMapForEachKey(filledMap(), 1, key -> {
            transformed.increment();
            checkLent(caller, helpers, outside);
            return key;
        }, key -> {
            acted.increment();
            checkLent(caller, helpers, outside);
        });
        AgentConcurrencyUtilHooks.concurrentMapForEachValue(filledMap(), 1, value -> {
            transformed.increment();
            return value;
        }, value -> {
            acted.increment();
            checkLent(caller, helpers, outside);
        });
        AgentConcurrencyUtilHooks.concurrentMapForEachEntry(filledMap(), 1, entry -> {
            transformed.increment();
            return entry.getKey();
        }, key -> {
            acted.increment();
            checkLent(caller, helpers, outside);
        });

        assertEquals(4L * ELEMENTS, transformed.sum(), "every mapping is transformed exactly once");
        assertEquals(ELEMENTS / 2 + 3L * ELEMENTS, acted.sum(),
                "the action runs once per non-null transformation, and never for a null one");
        assertHelpedInsideTheRun(helpers, outside);
    }

    @Test
    @DisplayName("a helper works for the run while it runs an Arrays.parallelSetAll generator")
    void aParallelSetAllHelperWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Object[] objects = new Object[ELEMENTS];
        AgentConcurrencyUtilHooks.arraysParallelSetAll(objects, i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        int[] ints = new int[ELEMENTS];
        AgentConcurrencyUtilHooks.arraysParallelSetAll(ints, i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        long[] longs = new long[ELEMENTS];
        AgentConcurrencyUtilHooks.arraysParallelSetAll(longs, i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        double[] doubles = new double[ELEMENTS];
        AgentConcurrencyUtilHooks.arraysParallelSetAll(doubles, i -> {
            checkLent(caller, helpers, outside);
            return i;
        });

        Object[] expectedObjects = new Object[ELEMENTS];
        Arrays.setAll(expectedObjects, i -> i);
        assertArrayEquals(expectedObjects, objects);
        int[] expectedInts = new int[ELEMENTS];
        Arrays.setAll(expectedInts, i -> i);
        assertArrayEquals(expectedInts, ints);
        assertEquals(ELEMENTS - 1L, longs[ELEMENTS - 1]);
        assertEquals(ELEMENTS - 1.0, doubles[ELEMENTS - 1]);
        assertHelpedInsideTheRun(helpers, outside);
    }

    @Test
    @DisplayName("two helpers' unguarded writes to one field race")
    void helpersRace() {
        installRun();
        Thread caller = Thread.currentThread();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object tally = new Object();
        Set<Thread> writers = ConcurrentHashMap.newKeySet();

        AgentConcurrencyUtilHooks.concurrentMapForEach(filledMap(), 1, (key, value) -> {
            pause();
            if (Thread.currentThread() != caller && writers.add(Thread.currentThread())) {
                detector.recordFieldWrite(tally, "count");
            }
        });
        if (!commonPoolCanHelp() || writers.size() < 2) {
            return; // two helpers are what make a race; one pool thread cannot show it
        }
        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "two helpers wrote one field with nothing ordering them, which is the bug the "
                        + "action can carry");
    }

    @Test
    @DisplayName("the caller's read after the operation is ordered after a helper's write")
    void theCallerIsOrderedAfterAHelper() {
        installRun();
        Thread caller = Thread.currentThread();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        AtomicBoolean written = new AtomicBoolean();

        AgentConcurrencyUtilHooks.arraysParallelSetAll(new int[ELEMENTS], i -> {
            pause();
            if (Thread.currentThread() != caller && written.compareAndSet(false, true)) {
                detector.recordFieldWrite(result, "value");
            }
            return i;
        });
        if (!written.get()) {
            return;
        }
        detector.recordFieldRead(result, "value");
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "parallelSetAll returns once every generator call is done, so what a helper wrote "
                        + "is ordered before the caller's next read");
    }

    @Test
    @DisplayName("outside a run every form runs as it was")
    void outsideARunNothingIsLent() {
        LongAdder seen = new LongAdder();
        AgentConcurrencyUtilHooks.concurrentMapForEach(filledMap(), 1, (key, value) -> seen.increment());
        int[] ints = new int[ELEMENTS];
        AgentConcurrencyUtilHooks.arraysParallelSetAll(ints, i -> {
            seen.increment();
            return i + 1;
        });
        assertEquals(2L * ELEMENTS, seen.sum());
        assertEquals(ELEMENTS, ints[ELEMENTS - 1]);
    }
}
