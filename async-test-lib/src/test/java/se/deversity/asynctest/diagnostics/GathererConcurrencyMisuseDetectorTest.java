package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link GathererConcurrencyMisuseDetector}.
 */
class GathererConcurrencyMisuseDetectorTest {

    private GathererConcurrencyMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new GathererConcurrencyMisuseDetector();
    }

    /** Run an integrator for {@code name} on two distinct platform threads. */
    private void integrateOnTwoThreads(String name) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable r = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) { return; }
            detector.recordIntegrate(name, Thread.currentThread());
        };
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        ready.await();
        go.countDown();
        a.join(); b.join();
    }

    // ---- Happy path ----

    @Test
    void noIssues_sequentialSingleThread() {
        detector.registerGatherer("g", false, false);
        Thread t = Thread.currentThread();
        detector.recordIntegrate("g", t);
        detector.recordIntegrate("g", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Single-thread integration is safe: " + report);
        assertEquals(2, report.getTotalIntegrations());
    }

    @Test
    void noIssues_parallelWithCombiner() throws Exception {
        detector.registerGatherer("g", true /* has combiner */, true);
        integrateOnTwoThreads("g");

        var report = detector.analyze();
        assertTrue(report.getMissingCombinerIssues().isEmpty(),
            "A combiner makes parallel use safe: " + report);
    }

    // ---- Missing combiner ----

    @Test
    void detectsMissingCombiner_whenParallelStatefulRunsMultiThread() throws Exception {
        detector.registerGatherer("g", false /* no combiner */, true);
        integrateOnTwoThreads("g");

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getMissingCombinerIssues().isEmpty());
        String issue = report.getMissingCombinerIssues().get(0);
        assertTrue(issue.contains("g"), issue);
        assertTrue(issue.contains("combiner"), issue);
    }

    @Test
    void parallelGathererWithACombinerIntegratingOnManyThreadsIsSilent() throws Exception {
        // Gatherer.of(initializer, integrator, combiner, finisher) on a parallel stream: the
        // runtime gives every segment its own state from the initializer and merges them with the
        // combiner. The integrator running on two threads is that contract working, not a race.
        detector.registerGatherer("g", true, true);
        integrateOnTwoThreads("g");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
            "A parallel gatherer with a combiner is the correct shape; integration on several "
                + "threads is how it runs: " + report);
    }

    // ---- Unregistered gatherer ----

    @Test
    void ignoresUnregisteredGatherer() {
        detector.recordIntegrate("never-registered", Thread.currentThread());
        assertFalse(detector.analyze().hasIssues());
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.registerGatherer(null, false, true);
            detector.recordIntegrate(null, Thread.currentThread());
            detector.recordIntegrate("g", null);
        });
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString()
            .contains("No unsafe parallel-gatherer usage"));
    }

    @Test
    void toString_containsLearningContent_whenIssuesFound() throws Exception {
        detector.registerGatherer("g", false, true);
        integrateOnTwoThreads("g");

        String str = detector.analyze().toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("Gatherer"), str);
        assertTrue(str.contains("combiner"), str);
        // The JDK evaluates a combiner-less gatherer sequentially and loses nothing (#777).
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(str), str);
    }

    @Test
    void sharedStateRaceOutranksAMissingCombinerInTheReportSeverity() throws Exception {
        detector.registerGatherer("sequential", false, true);
        integrateOnTwoThreads("sequential");
        detector.registerGatherer("shared", true, true);
        java.util.List<String> shared = new java.util.ArrayList<>();
        integrateStatesOnTwoThreads("shared", shared, shared);

        String str = detector.analyze().toString();
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(str),
            "A shared-state race is a data race and must set the report's severity: " + str);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("re-registering the same gatherer keeps earlier threads' observations")
    void reRegisteringTheSameGathererDoesNotDiscardObservations() throws Exception {
        GathererConcurrencyMisuseDetector detector = new GathererConcurrencyMisuseDetector();

        // Both workers register and then integrate, which is what a consumer writes when the
        // gatherer is built inside the concurrent body - an @AsyncTest body runs once per
        // thread, so registration happens per thread too. An unconditional put made the second
        // registration discard the first thread's id, so the "integrator ran on more than one
        // thread" test never reached two and the detector was silent under exactly the
        // parallelism it exists to police.
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        Runnable worker = () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            detector.registerGatherer("g", false /* no combiner */, true /* parallel */);
            detector.recordIntegrate("g", Thread.currentThread());
        };
        Thread t1 = new Thread(worker, "gatherer-1");
        Thread t2 = new Thread(worker, "gatherer-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertTrue(detector.analyze().hasIssues(),
            "Two threads integrated one parallel gatherer that declares no combiner, which is "
            + "the whole missing-combiner finding. Silence here "
            + "means a later registerGatherer call reset the observations of the earlier one; "
            + "registration must be first-wins, as it is in SharedMessageDigestDetector and "
            + "DaemonThreadHygieneDetector.");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("many threads arriving together still produce the finding")
    void manyThreadsReleasedTogetherStillReport() throws Exception {
        // An invariant guard, and deliberately not claimed as a regression test for the race it
        // came from. The emission used to be guarded by integratingThreadIds.size() == 2, an
        // equality test on a set the other workers add to at that same instant, so the thread
        // whose add took the set to two could read it back as five and no thread saw two at all.
        //
        // That surfaced in the corpus recording lane, which produced zero findings across 240
        // body executions, twice. Reproducing it here is another matter: measured against the
        // old code, sixteen and sixty-four threads on a barrier missed 0 times in 30, and 240
        // threads missed 1 in 30. A gate at that rate would be flaky, so this test does not try
        // to be one. It pins the plainer property - multi-thread integration reports at all -
        // and the corpus row is what actually goes red if the equality test comes back.
        for (int attempt = 0; attempt < 10; attempt++) {
            GathererConcurrencyMisuseDetector detector = new GathererConcurrencyMisuseDetector();
            detector.registerGatherer("wide", false, true);

            int threads = 16;
            java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(threads);
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        gate.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    detector.recordIntegrate("wide", Thread.currentThread());
                });
            }
            for (Thread w : workers) {
                w.start();
            }
            for (Thread w : workers) {
                w.join();
            }

            assertTrue(detector.analyze().hasIssues(),
                "Sixteen threads integrated one parallel gatherer with no combiner and the "
                + "detector reported nothing on attempt " + attempt + ". Whether a finding is "
                + "owed cannot depend on one thread happening to observe a particular set size "
                + "while the others are still adding to it: test for at least two distinct "
                + "threads and claim the report once, rather than for exactly two.");
        }
    }

    /** Two threads integrate {@code name}, the first against {@code stateA}, the second {@code stateB}. */
    private void integrateStatesOnTwoThreads(String name, Object stateA, Object stateB) throws Exception {
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        Object[] states = {stateA, stateB};
        Thread[] workers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            Object state = states[i];
            workers[i] = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                detector.recordIntegrate(name, state, Thread.currentThread());
            });
            workers[i].start();
        }
        for (Thread w : workers) {
            w.join();
        }
    }

    @Test
    void parallelGathererWithACombinerAndOneStatePerSegmentIsSilent() throws Exception {
        // Two segments, two states from the initializer: equal contents, distinct objects.
        detector.registerGatherer("g", true, true);
        integrateStatesOnTwoThreads("g", new java.util.ArrayList<String>(), new java.util.ArrayList<String>());

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
            "Each segment integrated its own state, which is the parallel contract: " + report);
    }

    @Test
    void parallelGathererWithACombinerSharingOneStateAcrossSegmentsFires() throws Exception {
        // An initializer returning a captured instance: every segment integrates the same object.
        detector.registerGatherer("g", true, true);
        java.util.List<String> shared = new java.util.ArrayList<>();
        integrateStatesOnTwoThreads("g", shared, shared);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "One state integrated on two threads races: " + report);
        assertFalse(report.getSharedStateIssues().isEmpty(), report.toString());
        assertTrue(report.toString().contains("HIGH"), report.toString());
    }

    @Test
    void oneLabelRegisteredWithTwoShapesDrawsNoMissingCombinerVerdict() throws Exception {
        // Two unrelated gatherers under one label: a combiner-less one and a parallel-safe one.
        // Which of them each integration belongs to cannot be recovered from the label.
        detector.registerGatherer("g", false, true);
        detector.registerGatherer("g", true, true);
        integrateOnTwoThreads("g");

        assertTrue(detector.analyze().getMissingCombinerIssues().isEmpty(),
            "A label registered with two shapes cannot support a verdict about one of them");
    }

    @Test
    void sequentialGathererWithoutCombinerOnManyThreadsIsNotMissingACombiner() throws Exception {
        detector.registerGatherer("seq", /* hasCombiner */ false, /* parallel */ false);
        integrateOnTwoThreads("seq");
        GathererConcurrencyMisuseDetector.GathererConcurrencyMisuseReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "A sequential gatherer needs no combiner however many threads run their own "
                + "sequential streams through it: " + report);
    }

    // ---- What the JDK does with a real Gatherer (JDK 24+, #777) ----
    //
    // The library compiles with release 21, where java.util.stream.Gatherer does not exist, so
    // these build a real Gatherer and call Stream.gather by reflection. On JDK 21 they are
    // skipped by assumption, which surefire reports as skipped rather than passed.

    /** First JDK where {@code java.util.stream.Gatherer} is final API (JEP 485). */
    private static final int GATHERER_FINAL_JDK = 24;

    /** Stream length; large enough that a parallel stream splits into several leaves. */
    private static final int ELEMENTS = 20_000;

    /** What one {@code parallel().gather(...)} run observed from inside its integrator. */
    private record GatherRun(List<Object> output, int states, int integratingThreads, boolean overlapped) { }

    private static void assumeGathererIsFinalApi() {
        assumeTrue(Runtime.version().feature() >= GATHERER_FINAL_JDK,
            "java.util.stream.Gatherer is final API from JDK " + GATHERER_FINAL_JDK
                + " (JEP 485); this JDK is " + Runtime.version());
    }

    private static List<Object> inOrder() {
        return new ArrayList<>(IntStream.range(0, ELEMENTS).boxed().toList());
    }

    /**
     * Runs {@code IntStream.range(0, ELEMENTS)} as a parallel stream through a real gatherer whose
     * state is a plain {@link ArrayList} and whose integrator reports every call to
     * {@code detector} under {@code label}, with its state and thread.
     *
     * <p>The stream runs in a four-thread pool so the thread count does not depend on the
     * runner's cores, and the last element's upstream step sleeps 20 ms. That holds the
     * rightmost segment back until its predecessors have integrated, so the JDK integrates it on
     * its own worker, a hand-off between threads. Without the delay one thread often drains the
     * whole chain: measured on JDK 26, 20 of 30 runs handed off in a four-thread pool and 11 of
     * 30 in a two-thread one; with it, 30 of 30 in both.
     *
     * @param withCombiner true builds {@code Gatherer.of(init, integrator, combiner, finisher)},
     *                     false builds {@code Gatherer.ofSequential(init, integrator, finisher)},
     *                     which has no combiner
     */
    private static GatherRun gatherInParallel(boolean withCombiner, GathererConcurrencyMisuseDetector detector,
                                              String label) throws Exception {
        Class<?> gathererType = Class.forName("java.util.stream.Gatherer");
        Class<?> integratorType = Class.forName("java.util.stream.Gatherer$Integrator");
        Class<?> greedyType = Class.forName("java.util.stream.Gatherer$Integrator$Greedy");
        Method push = Class.forName("java.util.stream.Gatherer$Downstream").getMethod("push", Object.class);

        AtomicInteger states = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        Set<Long> threads = ConcurrentHashMap.newKeySet();

        Supplier<List<Object>> initializer = () -> {
            states.incrementAndGet();
            return new ArrayList<>();
        };
        InvocationHandler integrate = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "integrator";
                };
            }
            @SuppressWarnings("unchecked")
            List<Object> state = (List<Object>) args[0];
            Thread self = Thread.currentThread();
            if (inFlight.incrementAndGet() != 1) {
                overlapped.set(true);
            }
            try {
                threads.add(self.threadId());
                detector.recordIntegrate(label, state, self);
                state.add(args[1]);
            } finally {
                inFlight.decrementAndGet();
            }
            return Boolean.TRUE;
        };
        // Greedy: the integrator never short-circuits, which lets the JDK run the upstream of
        // every segment in parallel ahead of the in-order integration.
        Object integrator = Proxy.newProxyInstance(GathererConcurrencyMisuseDetectorTest.class.getClassLoader(),
            new Class<?>[] {greedyType}, integrate);
        BinaryOperator<List<Object>> combiner = (left, right) -> {
            left.addAll(right);
            return left;
        };
        BiConsumer<List<Object>, Object> finisher = (state, downstream) -> {
            for (Object element : state) {
                try {
                    push.invoke(downstream, element);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Object gatherer = withCombiner
            ? gathererType.getMethod("of", Supplier.class, integratorType, BinaryOperator.class, BiConsumer.class)
                .invoke(null, initializer, integrator, combiner, finisher)
            : gathererType.getMethod("ofSequential", Supplier.class, integratorType, BiConsumer.class)
                .invoke(null, initializer, integrator, finisher);
        Method gather = Stream.class.getMethod("gather", gathererType);

        try (ForkJoinPool pool = new ForkJoinPool(4)) {
            List<?> output = pool.submit(() -> {
                Stream<Integer> upstream = IntStream.range(0, ELEMENTS).boxed().parallel().map(i -> {
                    if (i == ELEMENTS - 1) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return i;
                });
                try {
                    return ((Stream<?>) gather.invoke(upstream, gatherer)).toList();
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(e);
                }
            }).get();
            return new GatherRun(new ArrayList<>(output), states.get(), threads.size(), overlapped.get());
        }
    }

    @Test
    void jdkEvaluatesAGathererWithoutACombinerSequentiallyOnAParallelStream() throws Exception {
        // The premise #777 asked to establish. GathererOp sends a gatherer whose combiner is
        // Gatherer.defaultCombiner() down its "Hybrid" path: the upstream runs in parallel, but
        // one state is integrated segment by segment in encounter order, each segment's
        // completion happening-before the next. Nothing is lost and nothing races.
        assumeGathererIsFinalApi();

        GatherRun sequential = gatherInParallel(false, new GathererConcurrencyMisuseDetector(), "g");
        assertEquals(inOrder(), sequential.output(),
            "A gatherer without a combiner on a parallel stream lost, duplicated or reordered "
                + "elements; the JDK is documented to evaluate it sequentially");
        assertEquals(1, sequential.states(),
            "Without a combiner there is nothing to merge per-segment states with, so the JDK "
                + "must create exactly one state for the whole stream");
        assertFalse(sequential.overlapped(),
            "Two integrations of the one state ran at the same time; the JDK serializes them");

        // The twin: with a combiner the JDK really does split the state, one per segment, and
        // merges them in order with the combiner.
        GatherRun parallel = gatherInParallel(true, new GathererConcurrencyMisuseDetector(), "g");
        assertEquals(inOrder(), parallel.output(), "The combiner must merge every segment, in order");
        assertTrue(parallel.states() > 1,
            "A gatherer with a combiner on a parallel stream gets one state per segment, saw "
                + parallel.states());
    }

    @Test
    void detectorFedByARealGathererWithoutACombinerDoesNotClaimLostResults() throws Exception {
        assumeGathererIsFinalApi();

        GathererConcurrencyMisuseDetector detector = null;
        GatherRun run = null;
        for (int attempt = 0; attempt < 5 && (run == null || run.integratingThreads() < 2); attempt++) {
            detector = new GathererConcurrencyMisuseDetector();
            detector.registerGatherer("running", /* hasCombiner */ false, /* parallel */ true);
            run = gatherInParallel(false, detector, "running");
        }
        assumeTrue(run.integratingThreads() >= 2,
            "The JDK kept every integration on one thread in 5 runs, so the detector had no "
                + "multi-thread integration to judge");

        var report = detector.analyze();
        assertEquals(inOrder(), run.output(), "The JDK lost nothing in the run the detector judged");
        assertFalse(report.getMissingCombinerIssues().isEmpty(),
            "The detector should still say the gatherer ran without a combiner on a parallel "
                + "stream: " + report);
        String issue = report.getMissingCombinerIssues().get(0);
        assertFalse(issue.contains("lost"),
            "The JDK integrated one state in order on " + run.integratingThreads() + " threads and "
                + "lost nothing, yet the finding claims lost results: " + issue);
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report.toString()),
            "A correct, serialized gather stage is a missed speedup, not a HIGH defect: " + report);
        assertTrue(report.getSharedStateIssues().isEmpty(),
            "Handing the one state between threads is the JDK's design, not a shared-state race: "
                + report);

        // The twin: the same stream through a gatherer with a combiner, one state per segment.
        GathererConcurrencyMisuseDetector combined = new GathererConcurrencyMisuseDetector();
        combined.registerGatherer("running", /* hasCombiner */ true, /* parallel */ true);
        GatherRun parallel = gatherInParallel(true, combined, "running");
        assertEquals(inOrder(), parallel.output());
        assertFalse(combined.analyze().hasIssues(),
            "Each segment integrated its own state on its own thread: " + combined.analyze());
    }
}
