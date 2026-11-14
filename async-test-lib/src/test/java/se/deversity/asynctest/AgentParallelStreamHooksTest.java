package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.ArrayList;
import java.util.stream.Collector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.PrimitiveIterator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
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

    /**
     * A drain for the task events each helper publishes: with none, the ring fills after a few
     * thousand elements and every later event waits out the ring's give-up second, which turned
     * this class from seconds into most of an hour.
     */
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
    @DisplayName("a combiner stays ordered once the caller's clock is past the cap")
    @SuppressWarnings("unchecked")
    void aCombinerStaysOrderedPastTheClockCap() {
        // A long run starts thousands of workers, and the caller's clock fills with their ids,
        // all newer than the common pool's threads. A clock past the cap dropped the lowest ids, so
        // a combine on one pool thread lost the clock of the pool thread that filled what it
        // merges, and the correct collect reported as a race (corpus, JDK 25 and 26).
        numbers().forEach(value -> pause()); // the pool's threads exist before the crowd
        installRun();
        learnOfMoreThreadsThanAClockKeeps();
        RaceConditionDetector detector = new RaceConditionDetector();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

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
                "a container is merged only after the subtask that filled it finished, however many "
                        + "threads the caller met before: " + detector.analyzeRaceConditions());
    }

    /** HappensBefore.MAX_ENTRIES, package-private there: the most threads one clock keeps. */
    private static final int CLOCK_CAP = 4_096;

    /** Starts more threads than a clock keeps, each releasing one object, and acquires it. */
    private static void learnOfMoreThreadsThanAClockKeeps() {
        Object crowd = new Object();
        Thread[] threads = new Thread[CLOCK_CAP + 8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> HappensBefore.release(crowd), "crowd-" + i);
            threads[i].start();
        }
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        HappensBefore.acquire(crowd);
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

    // --- Primitive streams and the bridges to and from them (#1172) ----------------------------

    private static IntStream ints() {
        return IntStream.range(0, ELEMENTS).parallel();
    }

    private static LongStream longs() {
        return LongStream.range(0, ELEMENTS).parallel();
    }

    private static DoubleStream doubles() {
        return IntStream.range(0, ELEMENTS).asDoubleStream().parallel();
    }

    @Test
    @DisplayName("a helper works for the run while it runs a primitive stream's map, filter or peek lambda")
    void aPrimitivePipelineLambdaWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        IntStream intsMapped = AgentConcurrencyUtilHooks.intStreamMap(ints(), i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        IntStream intsFiltered = AgentConcurrencyUtilHooks.intStreamFilter(intsMapped, i -> {
            checkLent(caller, helpers, outside);
            return true;
        });
        int intSum = AgentConcurrencyUtilHooks.intStreamSum(AgentConcurrencyUtilHooks.intStreamPeek(intsFiltered,
                i -> checkLent(caller, helpers, outside)));

        LongStream longsMapped = AgentConcurrencyUtilHooks.longStreamMap(longs(), i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        LongStream longsFiltered = AgentConcurrencyUtilHooks.longStreamFilter(longsMapped, i -> {
            checkLent(caller, helpers, outside);
            return true;
        });
        long longSum = AgentConcurrencyUtilHooks.longStreamSum(AgentConcurrencyUtilHooks.longStreamPeek(
                longsFiltered, i -> checkLent(caller, helpers, outside)));

        DoubleStream doublesMapped = AgentConcurrencyUtilHooks.doubleStreamMap(doubles(), d -> {
            checkLent(caller, helpers, outside);
            return d;
        });
        DoubleStream doublesFiltered = AgentConcurrencyUtilHooks.doubleStreamFilter(doublesMapped, d -> {
            checkLent(caller, helpers, outside);
            return true;
        });
        double doubleSum = AgentConcurrencyUtilHooks.doubleStreamSum(AgentConcurrencyUtilHooks.doubleStreamPeek(
                doublesFiltered, d -> checkLent(caller, helpers, outside)));

        int total = ELEMENTS * (ELEMENTS - 1) / 2;
        assertEquals(total, intSum);
        assertEquals(total, longSum);
        assertEquals(total, doubleSum);
        assertFalse(outside.get(), "a helper ran a primitive pipeline lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a lambda stays lent across mapToInt, mapToLong, mapToDouble, mapToObj and boxed")
    void aLambdaStaysLentAcrossTheBridges() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        // Stream -> IntStream -> Stream: the first lambda runs only when toList does.
        IntStream asInts = AgentConcurrencyUtilHooks.streamMapToInt(numbers(), value -> {
            checkLent(caller, helpers, outside);
            return (Integer) value;
        });
        List<Object> objects = AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.intStreamMapToObj(asInts, i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                }));
        // IntStream -> boxed Stream, collected by a woven Stream terminal.
        Object boxed = AgentConcurrencyUtilHooks.streamCollect(AgentConcurrencyUtilHooks.intStreamBoxed(
                AgentConcurrencyUtilHooks.intStreamMap(ints(), i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                })), Collectors.toList());
        long asLongs = AgentConcurrencyUtilHooks.longStreamSum(AgentConcurrencyUtilHooks.streamMapToLong(numbers(),
                value -> {
                    checkLent(caller, helpers, outside);
                    return (Integer) value;
                }));
        java.util.OptionalDouble average = AgentConcurrencyUtilHooks.doubleStreamAverage(
                AgentConcurrencyUtilHooks.streamMapToDouble(numbers(), value -> {
                    checkLent(caller, helpers, outside);
                    return (Integer) value;
                }));

        assertEquals(ELEMENTS, objects.size());
        assertEquals(ELEMENTS, ((List<?>) boxed).size());
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2, asLongs);
        assertEquals((ELEMENTS - 1) / 2.0, average.orElseThrow());
        assertFalse(outside.get(), "a helper ran a lambda behind a bridge with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a helper works for the run while it runs a primitive reduce, collect or match lambda")
    @SuppressWarnings("unchecked")
    void aPrimitiveTerminalLambdaWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        int sum = AgentConcurrencyUtilHooks.intStreamReduce(ints(), 0, (a, b) -> {
            checkLent(caller, helpers, outside);
            return a + b;
        });
        Object collected = AgentConcurrencyUtilHooks.longStreamCollect(longs(), ArrayList::new, (list, value) -> {
            checkLent(caller, helpers, outside);
            ((List<Object>) list).add(value);
        }, (left, right) -> ((List<Object>) left).addAll((List<?>) right));
        boolean none = AgentConcurrencyUtilHooks.doubleStreamNoneMatch(doubles(), d -> {
            checkLent(caller, helpers, outside);
            return false;
        });
        AgentConcurrencyUtilHooks.intStreamForEachOrdered(ints(), i -> checkLent(caller, helpers, outside));

        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, sum);
        assertEquals(ELEMENTS, ((List<?>) collected).size());
        assertTrue(none);
        assertFalse(outside.get(), "a helper ran a primitive terminal lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("the caller is ordered after what a mapToInt lambda wrote once the sum returns")
    void theCallerIsOrderedAfterAMapToIntLambda() {
        installRun();
        Thread caller = Thread.currentThread();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object result = new Object();
        AtomicBoolean written = new AtomicBoolean();

        AgentConcurrencyUtilHooks.intStreamSum(AgentConcurrencyUtilHooks.streamMapToInt(numbers(), value -> {
            pause();
            if (Thread.currentThread() != caller && written.compareAndSet(false, true)) {
                detector.recordFieldWrite(result, "value");
            }
            return (Integer) value;
        }));
        if (!commonPoolCanHelp()) {
            return;
        }
        assertTrue(written.get(), "the common pool should have helped");
        detector.recordFieldRead(result, "value");
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "the sum has joined its tasks, so what a mapToInt lambda wrote is ordered before the "
                        + "caller's next read");
    }

    @Test
    @DisplayName("a primitive pipeline behind an unwoven terminal runs its lambdas as before")
    void anUnwovenPrimitiveTerminalLeavesThePipelineAlone() {
        installRun();
        Thread caller = Thread.currentThread();
        AtomicBoolean lentToAHelper = new AtomicBoolean();

        IntStream mapped = AgentConcurrencyUtilHooks.intStreamMap(ints(), i -> {
            if (Thread.currentThread() != caller && AsyncTestContext.agentContext() != null) {
                lentToAHelper.set(true);
            }
            return i;
        });
        // iterator() is no woven terminal: nothing would join the helpers, so none is lent the run.
        java.util.PrimitiveIterator.OfInt iterator = mapped.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            iterator.nextInt();
            count++;
        }

        assertEquals(ELEMENTS, count);
        assertFalse(lentToAHelper.get(), "a helper was lent the run with no join to end it");
    }

    @Test
    @DisplayName("every woven primitive stream form returns what the stream itself returns")
    @SuppressWarnings("unchecked")
    void everyPrimitiveFormReturnsTheStreamsOwnAnswer() {
        installRun();
        int total = ELEMENTS * (ELEMENTS - 1) / 2;
        int last = ELEMENTS - 1;

        assertEquals(IntStream.range(0, ELEMENTS).filter(i -> i % 3 == 0).map(i -> i * 2).sum(),
                AgentConcurrencyUtilHooks.intStreamSum(AgentConcurrencyUtilHooks.intStreamMap(
                        AgentConcurrencyUtilHooks.intStreamFilter(ints(), i -> i % 3 == 0), i -> i * 2)));
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.intStreamCount(ints()));
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.longStreamCount(longs()));
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.doubleStreamCount(doubles()));
        assertEquals((ELEMENTS - 1) / 2.0, AgentConcurrencyUtilHooks.intStreamAverage(ints()).orElseThrow());
        assertEquals((ELEMENTS - 1) / 2.0, AgentConcurrencyUtilHooks.longStreamAverage(longs()).orElseThrow());
        assertEquals((ELEMENTS - 1) / 2.0, AgentConcurrencyUtilHooks.doubleStreamAverage(doubles()).orElseThrow());
        assertEquals(java.util.OptionalInt.of(0), AgentConcurrencyUtilHooks.intStreamMin(ints()));
        assertEquals(java.util.OptionalInt.of(last), AgentConcurrencyUtilHooks.intStreamMax(ints()));
        assertEquals(java.util.OptionalLong.of(0), AgentConcurrencyUtilHooks.longStreamMin(longs()));
        assertEquals(java.util.OptionalLong.of(last), AgentConcurrencyUtilHooks.longStreamMax(longs()));
        assertEquals(java.util.OptionalDouble.of(0), AgentConcurrencyUtilHooks.doubleStreamMin(doubles()));
        assertEquals(java.util.OptionalDouble.of(last), AgentConcurrencyUtilHooks.doubleStreamMax(doubles()));
        assertEquals(total, AgentConcurrencyUtilHooks.intStreamSummaryStatistics(ints()).getSum());
        assertEquals(total, AgentConcurrencyUtilHooks.longStreamSummaryStatistics(longs()).getSum());
        assertEquals(total, AgentConcurrencyUtilHooks.doubleStreamSummaryStatistics(doubles()).getSum());
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.intStreamToArray(ints()).length);
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.longStreamToArray(longs()).length);
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.doubleStreamToArray(doubles()).length);
        assertEquals(java.util.OptionalInt.of(0), AgentConcurrencyUtilHooks.intStreamFindFirst(ints()));
        assertEquals(java.util.OptionalLong.of(0), AgentConcurrencyUtilHooks.longStreamFindFirst(longs()));
        assertEquals(java.util.OptionalDouble.of(0), AgentConcurrencyUtilHooks.doubleStreamFindFirst(doubles()));
        assertTrue(AgentConcurrencyUtilHooks.intStreamFindAny(ints()).isPresent());
        assertTrue(AgentConcurrencyUtilHooks.longStreamFindAny(longs()).isPresent());
        assertTrue(AgentConcurrencyUtilHooks.doubleStreamFindAny(doubles()).isPresent());
        assertEquals(java.util.OptionalInt.of(total), AgentConcurrencyUtilHooks.intStreamReduce(ints(), Integer::sum));
        assertEquals(java.util.OptionalLong.of(total), AgentConcurrencyUtilHooks.longStreamReduce(longs(), Long::sum));
        assertEquals(total, AgentConcurrencyUtilHooks.longStreamReduce(longs(), 0L, Long::sum));
        assertEquals(java.util.OptionalDouble.of(total),
                AgentConcurrencyUtilHooks.doubleStreamReduce(doubles(), Double::sum));
        assertEquals(total, AgentConcurrencyUtilHooks.doubleStreamReduce(doubles(), 0.0, Double::sum));
        assertTrue(AgentConcurrencyUtilHooks.intStreamAnyMatch(ints(), i -> i == 7));
        assertTrue(AgentConcurrencyUtilHooks.intStreamAllMatch(ints(), i -> i >= 0));
        assertTrue(AgentConcurrencyUtilHooks.intStreamNoneMatch(ints(), i -> i < 0));
        assertTrue(AgentConcurrencyUtilHooks.longStreamAnyMatch(longs(), i -> i == 7));
        assertTrue(AgentConcurrencyUtilHooks.longStreamAllMatch(longs(), i -> i >= 0));
        assertTrue(AgentConcurrencyUtilHooks.longStreamNoneMatch(longs(), i -> i < 0));
        assertTrue(AgentConcurrencyUtilHooks.doubleStreamAnyMatch(doubles(), d -> d == 7));
        assertTrue(AgentConcurrencyUtilHooks.doubleStreamAllMatch(doubles(), d -> d >= 0));
        assertEquals(ELEMENTS, ((java.util.BitSet) AgentConcurrencyUtilHooks.intStreamCollect(ints(),
                java.util.BitSet::new, (bits, i) -> ((java.util.BitSet) bits).set(i),
                (left, right) -> ((java.util.BitSet) left).or((java.util.BitSet) right))).cardinality());
        assertEquals(ELEMENTS, ((Set<?>) AgentConcurrencyUtilHooks.doubleStreamCollect(doubles(),
                java.util.HashSet::new, (set, d) -> ((Set<Object>) set).add(d),
                (left, right) -> ((Set<Object>) left).addAll((Set<?>) right))).size());
        List<Object> boxedLongs = AgentConcurrencyUtilHooks.streamToList(AgentConcurrencyUtilHooks.longStreamBoxed(longs()));
        assertEquals(LongStream.range(0, ELEMENTS).boxed().collect(Collectors.toList()), boxedLongs);
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(AgentConcurrencyUtilHooks.doubleStreamBoxed(doubles())));
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(
                AgentConcurrencyUtilHooks.longStreamMapToObj(longs(), i -> i)));
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(
                AgentConcurrencyUtilHooks.doubleStreamMapToObj(doubles(), d -> d)));
        List<Long> ordered = new ArrayList<>();
        AgentConcurrencyUtilHooks.longStreamForEachOrdered(longs(), ordered::add);
        assertEquals(LongStream.range(0, ELEMENTS).boxed().collect(Collectors.toList()), ordered);
        List<Double> orderedDoubles = new ArrayList<>();
        AgentConcurrencyUtilHooks.doubleStreamForEachOrdered(doubles(), orderedDoubles::add);
        assertEquals(ELEMENTS, orderedDoubles.size());
    }

    // --- Stages that run no lambda of their own, and the rest of the lambda stages (#1172) -------

    @Test
    @DisplayName("a map lambda behind sorted, distinct, limit, skip or parallel() stays lent")
    void aLambdaStaysLentBehindTheCarryingStages() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        // A sequential source made parallel after the lambda, the shape list.stream().map(...).parallel().
        @SuppressWarnings("unchecked")
        Stream<Object> sequential = (Stream<Object>) (Stream<?>) IntStream.range(0, ELEMENTS).boxed();
        Stream<Object> mapped = AgentConcurrencyUtilHooks.streamMap(sequential, value -> {
            checkLent(caller, helpers, outside);
            return value;
        });
        @SuppressWarnings("unchecked")
        Stream<Object> parallel = (Stream<Object>) AgentConcurrencyUtilHooks.streamParallel(mapped);
        Stream<Object> carried = AgentConcurrencyUtilHooks.streamSkip(AgentConcurrencyUtilHooks.streamLimit(
                AgentConcurrencyUtilHooks.streamDistinct(AgentConcurrencyUtilHooks.streamSorted(parallel)),
                ELEMENTS), 1);
        assertEquals(ELEMENTS - 1, AgentConcurrencyUtilHooks.streamCount(carried));

        IntStream ints = AgentConcurrencyUtilHooks.intStreamMap(IntStream.range(0, ELEMENTS), i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        IntStream carriedInts = AgentConcurrencyUtilHooks.intStreamSkip(AgentConcurrencyUtilHooks.intStreamLimit(
                AgentConcurrencyUtilHooks.intStreamDistinct(AgentConcurrencyUtilHooks.intStreamSorted(
                        AgentConcurrencyUtilHooks.intStreamParallel(ints))), ELEMENTS), 1);
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2,
                AgentConcurrencyUtilHooks.doubleStreamSum(AgentConcurrencyUtilHooks.longStreamAsDoubleStream(
                        AgentConcurrencyUtilHooks.intStreamAsLongStream(carriedInts))));

        assertFalse(outside.get(), "a helper ran a lambda behind a carrying stage with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a helper works for the run while it runs a flatMap, takeWhile, dropWhile or cross-primitive map lambda")
    void theRemainingLambdaStagesWorkForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Stream<Object> flat = AgentConcurrencyUtilHooks.streamFlatMap(numbers(), value -> {
            checkLent(caller, helpers, outside);
            return Stream.of(value);
        });
        Stream<Object> taken = AgentConcurrencyUtilHooks.streamTakeWhile(flat, value -> {
            checkLent(caller, helpers, outside);
            return true;
        });
        Stream<Object> dropped = AgentConcurrencyUtilHooks.streamDropWhile(taken, value -> {
            checkLent(caller, helpers, outside);
            return false;
        });
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(dropped));

        LongStream longs = AgentConcurrencyUtilHooks.intStreamMapToLong(
                AgentConcurrencyUtilHooks.intStreamFlatMap(ints(), i -> {
                    checkLent(caller, helpers, outside);
                    return IntStream.of(i);
                }), i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                });
        DoubleStream doubles = AgentConcurrencyUtilHooks.longStreamMapToDouble(
                AgentConcurrencyUtilHooks.longStreamTakeWhile(longs, i -> {
                    checkLent(caller, helpers, outside);
                    return true;
                }), i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                });
        IntStream back = AgentConcurrencyUtilHooks.doubleStreamMapToInt(
                AgentConcurrencyUtilHooks.doubleStreamDropWhile(doubles, d -> {
                    checkLent(caller, helpers, outside);
                    return false;
                }), d -> {
                    checkLent(caller, helpers, outside);
                    return (int) d;
                });
        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.intStreamSum(back));

        assertFalse(outside.get(), "a helper ran a stage lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a sorted comparator is ordered after the elements that built what it compares")
    void aSortedComparatorIsOrderedAfterTheElementsItCompares() {
        installRun();
        RaceConditionDetector detector = new RaceConditionDetector();

        // Each element is a fresh box written where it was made and read where it is compared.
        Stream<Object> boxes = AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
            pause();
            int[] box = {(Integer) value};
            detector.recordFieldWrite(box, "value");
            return box;
        });
        Stream<Object> sorted = AgentConcurrencyUtilHooks.streamSorted(boxes, (left, right) -> {
            detector.recordFieldRead(left, "value");
            detector.recordFieldRead(right, "value");
            return Integer.compare(((int[]) right)[0], ((int[]) left)[0]);
        });
        List<Object> descending = AgentConcurrencyUtilHooks.streamToList(sorted);
        assertEquals(ELEMENTS - 1, ((int[]) descending.get(0))[0]);
        if (!commonPoolCanHelp()) {
            return;
        }
        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "a parallel sort compares elements only after the subtasks that made them finished: "
                        + detector.analyzeRaceConditions());
    }

    @Test
    @DisplayName("every carrying and remaining lambda stage returns what the stream itself returns")
    void everyStageReturnsTheStreamsOwnAnswer() {
        installRun();
        java.util.Comparator<Object> byValue = (a, b) -> Integer.compare((Integer) a, (Integer) b);
        List<Object> expected = IntStream.range(0, ELEMENTS).boxed().collect(Collectors.toList());

        assertEquals(expected, AgentConcurrencyUtilHooks.streamToList(AgentConcurrencyUtilHooks.streamSorted(
                castObjects((Stream<?>) AgentConcurrencyUtilHooks.streamUnordered(numbers())), byValue)));
        assertEquals(expected.subList(0, 10), AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.streamLimit(numbers(), 10)));
        assertEquals(expected.subList(10, ELEMENTS), AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.streamSkip(numbers(), 10)));
        assertEquals(List.of(0, 1), AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.streamDistinct(castObjects(Stream.of(0, 1, 1, 0).parallel()))));
        assertEquals(expected.subList(0, 5), AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.streamTakeWhile(numbers(), v -> (Integer) v < 5)));
        assertEquals(expected.subList(5, ELEMENTS), AgentConcurrencyUtilHooks.streamToList(
                AgentConcurrencyUtilHooks.streamDropWhile(numbers(), v -> (Integer) v < 5)));
        assertEquals(2 * ELEMENTS, AgentConcurrencyUtilHooks.streamCount(
                AgentConcurrencyUtilHooks.streamFlatMap(numbers(), v -> Stream.of(v, v))));
        assertFalse(AgentConcurrencyUtilHooks.streamSequential(numbers()).isParallel());

        assertEquals(java.util.OptionalInt.of(0), AgentConcurrencyUtilHooks.intStreamFindFirst(
                AgentConcurrencyUtilHooks.intStreamSorted(IntStream.of(3, 0, 2).parallel())));
        assertEquals(2, AgentConcurrencyUtilHooks.intStreamCount(
                AgentConcurrencyUtilHooks.intStreamDistinct(IntStream.of(1, 1, 2).parallel())));
        assertEquals(3, AgentConcurrencyUtilHooks.longStreamCount(AgentConcurrencyUtilHooks.longStreamLimit(
                AgentConcurrencyUtilHooks.longStreamSorted(AgentConcurrencyUtilHooks.longStreamDistinct(longs())), 3)));
        assertEquals(ELEMENTS - 3, AgentConcurrencyUtilHooks.doubleStreamCount(AgentConcurrencyUtilHooks.doubleStreamSkip(
                AgentConcurrencyUtilHooks.doubleStreamSorted(AgentConcurrencyUtilHooks.doubleStreamDistinct(doubles())), 3)));
        assertEquals(ELEMENTS - 3, AgentConcurrencyUtilHooks.intStreamCount(AgentConcurrencyUtilHooks.intStreamSkip(
                AgentConcurrencyUtilHooks.intStreamLimit(ints(), ELEMENTS), 3)));
        assertEquals(1, AgentConcurrencyUtilHooks.longStreamCount(AgentConcurrencyUtilHooks.longStreamSkip(
                AgentConcurrencyUtilHooks.longStreamLimit(longs(), 2), 1)));
        assertEquals(1, AgentConcurrencyUtilHooks.doubleStreamCount(AgentConcurrencyUtilHooks.doubleStreamLimit(doubles(), 1)));
        assertTrue(AgentConcurrencyUtilHooks.longStreamParallel(LongStream.range(0, 3)).isParallel());
        assertTrue(AgentConcurrencyUtilHooks.doubleStreamParallel(DoubleStream.of(1)).isParallel());
        assertFalse(AgentConcurrencyUtilHooks.intStreamSequential(ints()).isParallel());
        assertFalse(AgentConcurrencyUtilHooks.longStreamSequential(longs()).isParallel());
        assertFalse(AgentConcurrencyUtilHooks.doubleStreamSequential(doubles()).isParallel());
        assertEquals(2 * ELEMENTS, AgentConcurrencyUtilHooks.longStreamCount(
                AgentConcurrencyUtilHooks.longStreamFlatMap(longs(), i -> LongStream.of(i, i))));
        assertEquals(2 * ELEMENTS, AgentConcurrencyUtilHooks.doubleStreamCount(
                AgentConcurrencyUtilHooks.doubleStreamFlatMap(doubles(), d -> DoubleStream.of(d, d))));
        assertEquals(5, AgentConcurrencyUtilHooks.intStreamCount(AgentConcurrencyUtilHooks.intStreamTakeWhile(ints(), i -> i < 5)));
        assertEquals(ELEMENTS - 5, AgentConcurrencyUtilHooks.intStreamCount(AgentConcurrencyUtilHooks.intStreamDropWhile(ints(), i -> i < 5)));
        assertEquals(ELEMENTS - 5, AgentConcurrencyUtilHooks.longStreamCount(AgentConcurrencyUtilHooks.longStreamDropWhile(longs(), i -> i < 5)));
        assertEquals(5, AgentConcurrencyUtilHooks.doubleStreamCount(AgentConcurrencyUtilHooks.doubleStreamTakeWhile(doubles(), d -> d < 5)));
        int total = ELEMENTS * (ELEMENTS - 1) / 2;
        assertEquals(total, AgentConcurrencyUtilHooks.doubleStreamSum(AgentConcurrencyUtilHooks.intStreamMapToDouble(ints(), i -> i)));
        assertEquals(total, AgentConcurrencyUtilHooks.doubleStreamSum(AgentConcurrencyUtilHooks.intStreamAsDoubleStream(ints())));
        assertEquals(total, AgentConcurrencyUtilHooks.intStreamSum(AgentConcurrencyUtilHooks.longStreamMapToInt(longs(), i -> (int) i)));
        assertEquals(total, AgentConcurrencyUtilHooks.longStreamSum(AgentConcurrencyUtilHooks.doubleStreamMapToLong(doubles(), d -> (long) d)));
    }

    @SuppressWarnings("unchecked")
    private static Stream<Object> castObjects(Stream<?> stream) {
        return (Stream<Object>) stream;
    }

    // --- The rest of #1172: mapMulti, a primitive unordered() or onClose, a Collector, iterator() ---

    @Test
    @DisplayName("a helper works for the run while it runs a mapMulti lambda, boxed or primitive")
    void aMapMultiLambdaWorksForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Stream<Object> multi = AgentConcurrencyUtilHooks.streamMapMulti(numbers(), (value, sink) -> {
            checkLent(caller, helpers, outside);
            sink.accept(value);
        });
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(multi));
        IntStream toInt = AgentConcurrencyUtilHooks.streamMapMultiToInt(numbers(), (value, sink) -> {
            checkLent(caller, helpers, outside);
            sink.accept((Integer) value);
        });
        IntStream ints = AgentConcurrencyUtilHooks.intStreamMapMulti(toInt, (i, sink) -> {
            checkLent(caller, helpers, outside);
            sink.accept(i);
        });
        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.intStreamSum(ints));
        LongStream longs = AgentConcurrencyUtilHooks.longStreamMapMulti(
                AgentConcurrencyUtilHooks.streamMapMultiToLong(numbers(), (value, sink) -> {
                    checkLent(caller, helpers, outside);
                    sink.accept((Integer) value);
                }), (i, sink) -> {
                    checkLent(caller, helpers, outside);
                    sink.accept(i);
                });
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.longStreamSum(longs));
        DoubleStream doubles = AgentConcurrencyUtilHooks.doubleStreamMapMulti(
                AgentConcurrencyUtilHooks.streamMapMultiToDouble(numbers(), (value, sink) -> {
                    checkLent(caller, helpers, outside);
                    sink.accept((Integer) value);
                }), (d, sink) -> {
                    checkLent(caller, helpers, outside);
                    sink.accept(d);
                });
        assertEquals((double) ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.doubleStreamSum(doubles));

        assertFalse(outside.get(), "a helper ran a mapMulti lambda with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a map lambda behind a primitive unordered() or any onClose stays lent")
    void aLambdaStaysLentBehindUnorderedAndOnClose() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();
        AtomicInteger closed = new AtomicInteger();

        IntStream ints = AgentConcurrencyUtilHooks.intStreamMap(ints(), i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        IntStream carriedInts = (IntStream) AgentConcurrencyUtilHooks.intStreamOnClose(
                (IntStream) AgentConcurrencyUtilHooks.intStreamUnordered(ints), closed::incrementAndGet);
        assertEquals(ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.intStreamSum(carriedInts));
        LongStream longs = AgentConcurrencyUtilHooks.longStreamMap(longs(), i -> {
            checkLent(caller, helpers, outside);
            return i;
        });
        LongStream carriedLongs = (LongStream) AgentConcurrencyUtilHooks.longStreamOnClose(
                (LongStream) AgentConcurrencyUtilHooks.longStreamUnordered(longs), closed::incrementAndGet);
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.longStreamSum(carriedLongs));
        DoubleStream doubles = AgentConcurrencyUtilHooks.doubleStreamMap(doubles(), d -> {
            checkLent(caller, helpers, outside);
            return d;
        });
        DoubleStream carriedDoubles = (DoubleStream) AgentConcurrencyUtilHooks.doubleStreamOnClose(
                (DoubleStream) AgentConcurrencyUtilHooks.doubleStreamUnordered(doubles), closed::incrementAndGet);
        assertEquals((double) ELEMENTS * (ELEMENTS - 1) / 2, AgentConcurrencyUtilHooks.doubleStreamSum(carriedDoubles));
        @SuppressWarnings("unchecked")
        Stream<Object> objects = (Stream<Object>) AgentConcurrencyUtilHooks.streamOnClose(
                AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
                    checkLent(caller, helpers, outside);
                    return value;
                }), closed::incrementAndGet);
        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCount(objects));
        objects.close();
        carriedInts.close();

        assertEquals(2, closed.get(), "onClose keeps the close handler it was given");
        assertFalse(outside.get(), "a helper ran a lambda behind unordered() or onClose with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a helper works for the run while it runs a Collector's own supplier, accumulator or combiner")
    void aCollectorsFunctionsWorkForTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        Collector<Object, List<Object>, Integer> counting = Collector.of(
                () -> {
                    checkLent(caller, helpers, outside);
                    return new ArrayList<>();
                },
                (list, value) -> {
                    checkLent(caller, helpers, outside);
                    list.add(value);
                },
                (left, right) -> {
                    checkLent(caller, helpers, outside);
                    left.addAll(right);
                    return left;
                },
                List::size);

        assertEquals(ELEMENTS, AgentConcurrencyUtilHooks.streamCollect(numbers(), counting),
                "the finisher still runs");
        assertEquals(ELEMENTS, ((List<?>) AgentConcurrencyUtilHooks.streamCollect(numbers(),
                Collectors.toList())).size(), "an identity-finishing collector still returns its container");
        assertFalse(outside.get(), "a helper ran a Collector's function with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("a pipeline ended by iterator() lends its lambdas the run while the caller iterates")
    void anIteratorLendsThePipelineTheRun() {
        installRun();
        Thread caller = Thread.currentThread();
        Set<Thread> helpers = ConcurrentHashMap.newKeySet();
        AtomicBoolean outside = new AtomicBoolean();

        // sorted makes the first hasNext evaluate the whole upstream in parallel, on the helpers.
        Iterator<Object> objects = AgentConcurrencyUtilHooks.streamIterator(AgentConcurrencyUtilHooks.streamSorted(
                AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
                    checkLent(caller, helpers, outside);
                    return value;
                })));
        int count = 0;
        while (objects.hasNext()) {
            objects.next();
            count++;
        }
        assertEquals(ELEMENTS, count);
        PrimitiveIterator.OfInt ints = AgentConcurrencyUtilHooks.intStreamIterator(
                AgentConcurrencyUtilHooks.intStreamSorted(AgentConcurrencyUtilHooks.intStreamMap(ints(), i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                })));
        long sum = 0;
        while (ints.hasNext()) {
            sum += ints.nextInt();
        }
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2, sum);
        long[] longSum = new long[1];
        AgentConcurrencyUtilHooks.longStreamIterator(AgentConcurrencyUtilHooks.longStreamSorted(
                AgentConcurrencyUtilHooks.longStreamMap(longs(), i -> {
                    checkLent(caller, helpers, outside);
                    return i;
                }))).forEachRemaining((long i) -> longSum[0] += i);
        assertEquals((long) ELEMENTS * (ELEMENTS - 1) / 2, longSum[0]);
        double[] doubleSum = new double[1];
        AgentConcurrencyUtilHooks.doubleStreamIterator(AgentConcurrencyUtilHooks.doubleStreamSorted(
                AgentConcurrencyUtilHooks.doubleStreamMap(doubles(), d -> {
                    checkLent(caller, helpers, outside);
                    return d;
                }))).forEachRemaining((double d) -> doubleSum[0] += d);
        assertEquals((double) ELEMENTS * (ELEMENTS - 1) / 2, doubleSum[0]);

        assertFalse(outside.get(), "a helper ran a lambda behind iterator() with no run lent to it");
        assertEquals(commonPoolCanHelp(), !helpers.isEmpty(),
                "the common pool should have helped, or this test observed nothing");
    }

    @Test
    @DisplayName("the caller is ordered after what a map lambda wrote once hasNext returns")
    void theCallerIsOrderedAfterAnIteratedMapLambda() {
        installRun();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object[] cells = new Object[ELEMENTS];
        for (int i = 0; i < ELEMENTS; i++) {
            cells[i] = new Object();
        }
        Iterator<Object> objects = AgentConcurrencyUtilHooks.streamIterator(AgentConcurrencyUtilHooks.streamSorted(
                AgentConcurrencyUtilHooks.streamMap(numbers(), value -> {
                    pause();
                    detector.recordFieldWrite(cells[(Integer) value], "value");
                    return value;
                })));
        while (objects.hasNext()) {
            detector.recordFieldRead(cells[(Integer) objects.next()], "value");
        }

        assertFalse(detector.analyzeRaceConditions().hasIssues(),
                "each read follows the hasNext that waited for the write; the iterator joins its helpers");
    }

    // --- forEachOrdered: one action happens-before the next, on whichever threads they run -----

    /** One of the four {@code forEachOrdered} forms, given what to run for each element. */
    private interface OrderedForm {
        void run(Runnable action);
    }

    /**
     * The four forms, each behind a slow unwoven stage: a leaf's thread runs the action for its
     * elements once the leaves before it are done, so slow leaves are what spreads the actions
     * over several threads. With a slow action and fast leaves the caller runs them all.
     */
    private static Map<String, OrderedForm> orderedForms() {
        Map<String, OrderedForm> forms = new java.util.LinkedHashMap<>();
        forms.put("Stream", action -> AgentConcurrencyUtilHooks.streamForEachOrdered(
                numbers().peek(value -> pause()), value -> action.run()));
        forms.put("IntStream", action -> AgentConcurrencyUtilHooks.intStreamForEachOrdered(
                IntStream.range(0, ELEMENTS).parallel().peek(value -> pause()), value -> action.run()));
        forms.put("LongStream", action -> AgentConcurrencyUtilHooks.longStreamForEachOrdered(
                java.util.stream.LongStream.range(0, ELEMENTS).parallel().peek(value -> pause()),
                value -> action.run()));
        forms.put("DoubleStream", action -> AgentConcurrencyUtilHooks.doubleStreamForEachOrdered(
                java.util.stream.DoubleStream.of(new double[ELEMENTS]).parallel().peek(value -> pause()),
                value -> action.run()));
        return forms;
    }

    @Test
    @DisplayName("forEachOrdered actions are ordered one after the other, whichever threads run them")
    void orderedActionsFollowOneAnother() {
        installRun();
        for (Map.Entry<String, OrderedForm> form : orderedForms().entrySet()) {
            Set<Thread> threads = ConcurrentHashMap.newKeySet();
            // Which thread runs an action is the pool's choice; a few tries find a spread.
            for (int attempt = 0; attempt < 5 && threads.size() < 2; attempt++) {
                RaceConditionDetector detector = new RaceConditionDetector();
                Object total = new Object();
                threads.clear();

                form.getValue().run(() -> {
                    threads.add(Thread.currentThread());
                    detector.recordFieldRead(total, "value");
                    detector.recordFieldWrite(total, "value");
                });
                detector.recordFieldRead(total, "value");

                assertFalse(detector.analyzeRaceConditions().hasIssues(), form.getKey()
                        + ".forEachOrdered runs one action at a time, each after the one before it, "
                        + "so a field only its action touches is never raced; ran on "
                        + threads.size() + " thread(s)");
            }
            assertEquals(commonPoolCanHelp(), threads.size() > 1, form.getKey()
                    + ": the actions should have run on several threads, or this test observed nothing");
        }
    }

    @Test
    @DisplayName("the same action under forEach is still unordered")
    void unorderedActionsStillRace() {
        installRun();
        RaceConditionDetector detector = new RaceConditionDetector();
        Object total = new Object();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        AgentConcurrencyUtilHooks.intStreamForEach(IntStream.range(0, ELEMENTS).parallel(), value -> {
            pause();
            threads.add(Thread.currentThread());
            detector.recordFieldRead(total, "value");
            detector.recordFieldWrite(total, "value");
        });

        assertEquals(commonPoolCanHelp(), threads.size() > 1,
                "the common pool should have helped, or this test observed nothing");
        if (threads.size() > 1) {
            assertTrue(detector.analyzeRaceConditions().hasIssues(),
                    "forEach promises no order between its actions");
        }
    }
}
