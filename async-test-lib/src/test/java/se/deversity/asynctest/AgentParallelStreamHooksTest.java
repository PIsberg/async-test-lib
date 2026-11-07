package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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

    // --- Lambdas given to the pipeline and to other terminal operations (#1172) ----------------

    /** A parallel stream of {@link #ELEMENTS} boxed integers. */
    @SuppressWarnings("unchecked")
    private static Stream<Object> numbers() {
        return (Stream<Object>) (Stream<?>) IntStream.range(0, ELEMENTS).boxed().parallel();
    }

    /** Records, in {@code outside}, a helper that ran user code with no run lent to it. */
    private static void checkLent(Thread caller, Set<Thread> helpers, AtomicBoolean outside) {
        pause();
        if (Thread.currentThread() != caller) {
            helpers.add(Thread.currentThread());
            if (AsyncTestContext.agentContext() == null) {
                outside.set(true);
            }
        }
    }

    @Test
    @DisplayName("a helper works for the run while it runs a map, filter or peek lambda")
    void aPipelineLambdaWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Stream<Object> mapped = AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
            checkLent(caller, helpers, outside);
            return value;
        });
        Stream<Object> filtered = AgentConcurrencyUtilHooks.streamFilter(mapped, value -> {
            checkLent(caller, helpers, outside);
            return true;
        });
        Stream<Object> peeked = AgentConcurrencyUtilHooks.streamPeek(filtered,
                value -> checkLent(caller, helpers, outside));
        Object collected = AgentConcurrencyUtilHooks.streamCollect(peeked, Collectors.toList());

        assertEquals(ELEMENTS, ((List<?>) collected).size());
        assertFalse(outside.get(), "a helper ran a pipeline lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a helper works for the run while it runs a reduce, collect or match lambda")
    @SuppressWarnings("unchecked")
    void aTerminalLambdaWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Object sum = AgentConcurrencyUtilHooks.streamReduce(numbers(), 0, (a, b) -> {
            checkLent(caller, helpers, outside);
            return (Integer) a + (Integer) b;
        });
        AgentConcurrencyUtilHooks.streamCollect(numbers(), ArrayList::new, (list, value) -> {
            checkLent(caller, helpers, outside);
            ((List<Object>) list).add(value);
        }, (left, right) -> ((List<Object>) left).addAll((List<?>) right));
        boolean none = AgentConcurrencyUtilHooks.streamNoneMatch(numbers(), value -> {
            checkLent(caller, helpers, outside);
            return false;
        });

        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, sum);
        assertTrue(none);
        assertFalse(outside.get(), "a helper ran a terminal lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("the caller is ordered after what a map lambda wrote once the collect returns")
    void theCallerIsOrderedAfterAMapLambda() {
        installRun();
        Thread caller = Thread.currentThread();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        AtomicBoolean written = new AtomicBoolean();

        AgentConcurrencyUtilHooks.streamCollect(AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
            pause();
            if (Thread.currentThread() != caller && written.compareAndSet(false, true)) {
                detector.recordFieldWrite(result, "value");
            }
            return value;
        }), Collectors.toList());
        if (!commonPoolCanHelp()) {
            return;
        }
        assertTrue(written.get(), "the common pool should have helped");
        detector.recordFieldRead(result, "value");
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "the collect has joined its tasks, so what a map lambda wrote is ordered before the "
                        + "caller's next read");
    }

    @Test
    @DisplayName("a combiner is ordered after the elements that built what it merges")
    @SuppressWarnings("unchecked")
    void aCombinerIsOrderedAfterTheElementsItMerges() {
        installRun();
        RaceConditionDetector detector = new RaceConditionDetector();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        // The textbook correct collect: one container per subtask, merged once both are done.
        AgentConcurrencyUtilHooks.streamCollect(numbers(), ArrayList::new, (list, value) -> {
            pause();
            threads.add(Thread.currentThread());
            detector.recordFieldWrite(list, "size");
            ((List<Object>) list).add(value);
        }, (left, right) -> {
            detector.recordFieldRead(right, "size");
            detector.recordFieldWrite(left, "size");
            ((List<Object>) left).addAll((List<?>) right);
        });
        if (!commonPoolCanHelp()) {
            return;
        }
        assertTrue(threads.size() > 1, "the common pool should have helped");
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "a container is merged only after the subtask that filled it finished: "
                        + detector.analyzeRaceConditions());
    }

    @Test
    @DisplayName("a reduce operator is ordered after the elements that built what it combines")
    void aReduceOperatorIsOrderedAfterTheElementsItCombines() {
        installRun();
        RaceConditionDetector detector = new RaceConditionDetector();

        // Each partial result is a fresh object written where it was made and read where merged.
        Stream<Object> boxes = AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
            pause();
            int[] box = {(Integer) value};
            detector.recordFieldWrite(box, "value");
            return box;
        });
        Object total = AgentConcurrencyUtilHooks.streamReduce(boxes, (left, right) -> {
            detector.recordFieldRead(left, "value");
            detector.recordFieldRead(right, "value");
            int[] sum = {((int[]) left)[0] + ((int[]) right)[0]};
            detector.recordFieldWrite(sum, "value");
            return sum;
        });
        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, ((int[]) ((java.util.Optional<?>) total).orElseThrow())[0]);
        if (!commonPoolCanHelp()) {
            return;
        }
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "partial results are combined only after the subtasks that made them finished: "
                        + detector.analyzeRaceConditions());
    }

    @Test
    @DisplayName("a pipeline whose terminal operation is not woven runs its lambdas as before")
    void anUnwovenTerminalLeavesThePipelineAlone() {
        installRun();
        Thread caller = Thread.currentThread();
        AtomicBoolean lentToAHelper = new AtomicBoolean();

        Stream<Object> mapped = AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
            if (Thread.currentThread() != caller && AsyncTestContext.agentContext() != null) {
                lentToAHelper.set(true);
            }
            return value;
        });
        // iterator() is no woven terminal: nothing would join the helpers, so none is lent the run.
        java.util.Iterator<Object> iterator = mapped.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }

        assertEquals(ELEMENTS, count);
        assertFalse(lentToAHelper.get(), "a helper was lent the run with no join to end it");
    }

    @Test
    @DisplayName("every woven stream form returns what the stream itself returns")
    void everyFormReturnsTheStreamsOwnAnswer() {
        installRun();
        List<Object> expected = IntStream.range(0, ELEMENTS).filter(i -> i % 3 == 0).map(i -> i * 2)
                .boxed().collect(Collectors.toList());
        java.util.Comparator<Object> byValue = (a, b) -> Integer.compare((Integer) a, (Integer) b);
        int total = ELEMENTS * (ELEMENTS - 1) / 2;

        Stream<Object> filtered = AgentConcurrencyUtilHooks.streamFilter(numbers(),
                value -> (Integer) value % 3 == 0);
        Stream<Object> doubled = AgentConcurrencyUtilHooks.streamMap(filtered, value -> (Integer) value * 2);
        assertEquals(expected, AgentConcurrencyUtilHooks.streamToList(doubled));

        assertEquals((long) ELEMENTS, AgentConcurrencyUtilHooks.streamCount(numbers()));
        assertEquals(java.util.Optional.of(ELEMENTS - 1), AgentConcurrencyUtilHooks.streamMax(numbers(), byValue));
        assertEquals(java.util.Optional.of(0), AgentConcurrencyUtilHooks.streamMin(numbers(), byValue));
        assertEquals(java.util.Optional.of(total),
                AgentConcurrencyUtilHooks.streamReduce(numbers(), (a, b) -> (Integer) a + (Integer) b));
        assertEquals(total, AgentConcurrencyUtilHooks.streamReduce(numbers(), 0,
                (a, b) -> (Integer) a + (Integer) b, (a, b) -> (Integer) a + (Integer) b));
        assertTrue(AgentConcurrencyUtilHooks.streamAnyMatch(numbers(), value -> (Integer) value == 7));
        assertTrue(AgentConcurrencyUtilHooks.streamAllMatch(numbers(), value -> (Integer) value >= 0));
        assertEquals(java.util.Optional.of(0), AgentConcurrencyUtilHooks.streamFindFirst(numbers()));
        assertTrue(AgentConcurrencyUtilHooks.streamFindAny(numbers()).isPresent());
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamToArray(numbers()).length);
        List<Object> ordered = new ArrayList<>();
        AgentConcurrencyUtilHooks.streamForEachOrdered(numbers(), ordered::add);
        assertEquals(IntStream.range(0, ELEMENTS).boxed().collect(Collectors.toList()), ordered);
    }
}
