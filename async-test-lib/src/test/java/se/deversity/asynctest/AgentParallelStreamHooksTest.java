package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A parallel {@code forEach} the run started carries the run to the pool threads that help it
 * (#1156): nothing else attributes them, so a race among them was seen by no detector.
 */
class AgentParallelStreamHooksTest {

    /** Enough elements, each slow enough, that the common pool's workers join in. */
    private static final int ELEMENTS = 256;

    @AfterEach
    void leaveTheRun() {
        AsyncTestContext.uninstall();
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
        return ForkJoinPool.commonPool().getParallelism() > 0;
    }

    @Test
    @DisplayName("a helping pool thread works for the run while it runs an element")
    void aHelperWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outsideTheRun = new AtomicBoolean();

        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, ELEMENTS).parallel(), i -> {
            pause();
            if (Thread.currentThread() != caller) {
                helpers.add(Thread.currentThread());
                if (AsyncTestContext.agentContext() == null) {
                    outsideTheRun.set(true);
                }
            }
        });

        assertFalse(outsideTheRun.get(), "a helper ran an element with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a helper's loan ends with the element")
    void aHelpersLoanEndsWithTheElement() throws Exception {
        installRun();
        AtomicReference<Thread> helper = new AtomicReference<>();
        Thread caller = Thread.currentThread();

        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, ELEMENTS).parallel(), i -> {
            pause();
            if (Thread.currentThread() != caller) {
                helper.set(Thread.currentThread());
            }
        });
        if (!commonPoolCanHelp()) {
            return;
        }
        assertNotNull(helper.get(), "the common pool should have helped");

        // Tasks a helper runs after the operation, with nothing lent, must see no run.
        AtomicBoolean anyLeft = new AtomicBoolean();
        ForkJoinPool.commonPool().submit(() -> IntStream.range(0, ELEMENTS).parallel().forEach(i -> {
            pause();
            if (Thread.currentThread() != caller && AsyncTestContext.agentContext() != null) {
                anyLeft.set(true);
            }
        })).get();
        assertFalse(anyLeft.get(), "a pool thread kept working for the run after its element ended");
    }

    @Test
    @DisplayName("the caller is ordered after every helper once the operation returns")
    void theCallerIsOrderedAfterTheHelpers() {
        installRun();
        Thread caller = Thread.currentThread();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        AtomicBoolean written = new AtomicBoolean();

        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, ELEMENTS).parallel(), i -> {
            pause();
            if (Thread.currentThread() != caller && written.compareAndSet(false, true)) {
                detector.recordFieldWrite(result, "value");
            }
        });
        if (!commonPoolCanHelp()) {
            return;
        }
        assertTrue(written.get(), "the common pool should have helped");
        detector.recordFieldRead(result, "value");
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "a terminal operation has joined its tasks, so what a helper wrote is ordered before "
                        + "the caller's next read");
    }

    @Test
    @DisplayName("a sequential stream takes the call as it was, on the caller alone")
    void aSequentialStreamIsLeftAlone() {
        installRun();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, 8), i -> threads.add(Thread.currentThread()));

        assertEquals(Set.of(Thread.currentThread()), threads);
    }

    @Test
    @DisplayName("every element runs, exactly once, through each stream form")
    void everyElementRuns() {
        installRun();
        java.util.concurrent.atomic.LongAdder seen = new java.util.concurrent.atomic.LongAdder();
        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, 100).parallel(), i -> seen.increment());
        AgentConcurrencyUtilHooks.longStreamForEach(java.util.stream.LongStream.range(0, 100).parallel(),
                i -> seen.increment());
        AgentConcurrencyUtilHooks.doubleStreamForEach(java.util.stream.DoubleStream.of(new double[100]).parallel(),
                i -> seen.increment());
        AgentConcurrencyUtilHooks.streamForEach(
                java.util.stream.Stream.<Object>of(new Object[100]).parallel().map(o -> "x"),
                value -> seen.increment());
        assertEquals(400, seen.sum());
    }
}
