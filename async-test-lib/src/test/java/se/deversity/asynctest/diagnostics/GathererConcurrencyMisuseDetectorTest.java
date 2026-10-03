package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
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
     * What the integrator does around its one {@code state.add(element)}: report to a detector,
     * then run {@code body}, the add itself.
     */
    @FunctionalInterface
    private interface Integration {
        void around(Collection<Object> state, Thread self, Runnable body) throws Exception;
    }

    /**
     * Runs {@code IntStream.range(0, ELEMENTS)} as a parallel stream through a real gatherer whose
     * state is a plain {@link ArrayList} and whose integrator reports every call to
     * {@code detector} under {@code label}, with its state and thread.
     *
     * @param withCombiner true builds {@code Gatherer.of(init, integrator, combiner, finisher)},
     *                     false builds {@code Gatherer.ofSequential(init, integrator, finisher)},
     *                     which has no combiner
     */
    private static GatherRun gatherInParallel(boolean withCombiner, GathererConcurrencyMisuseDetector detector,
                                              String label) throws Exception {
        return gatherInParallel(withCombiner, (state, self, body) -> {
            detector.recordIntegrate(label, state, self);
            body.run();
        });
    }

    /**
     * Runs {@code IntStream.range(0, ELEMENTS)} as a parallel stream through a real gatherer whose
     * state is a fresh {@link ArrayList} per initializer call, with {@code integration} wrapped
     * around every {@code state.add(element)}, and records what the integrator saw.
     *
     * @param withCombiner true builds {@code Gatherer.of(init, integrator, combiner, finisher)},
     *                     false builds {@code Gatherer.ofSequential(init, integrator, finisher)},
     *                     which has no combiner
     * @param integration  how each integration reports to a detector; it must run its body once
     */
    private static GatherRun gatherInParallel(boolean withCombiner, Integration integration) throws Exception {
        AtomicInteger states = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        Set<Long> threads = ConcurrentHashMap.newKeySet();

        Supplier<Collection<Object>> initializer = () -> {
            states.incrementAndGet();
            return new ArrayList<>();
        };
        Object gatherer = newGatherer(withCombiner, initializer, (state, self, body) -> {
            if (inFlight.incrementAndGet() != 1) {
                overlapped.set(true);
            }
            try {
                threads.add(self.threadId());
                integration.around(state, self, body);
            } finally {
                inFlight.decrementAndGet();
            }
        });
        List<Object> output = gather(gatherer, ELEMENTS);
        return new GatherRun(output, states.get(), threads.size(), overlapped.get());
    }

    /**
     * Builds a real gatherer by reflection. Its integrator is an {@code Integrator.Greedy} that runs
     * {@code integration} around adding the element to its state, and its finisher pushes the
     * state's elements downstream.
     *
     * @param withCombiner true builds {@code Gatherer.of(init, integrator, combiner, finisher)},
     *                     false builds {@code Gatherer.ofSequential(init, integrator, finisher)},
     *                     which has no combiner
     * @param initializer  the gatherer's initializer, called once per state the JDK asks for
     * @param integration  runs around every {@code state.add(element)}; it must run its body once
     */
    private static Object newGatherer(boolean withCombiner, Supplier<? extends Collection<Object>> initializer,
                                      Integration integration) throws Exception {
        Class<?> gathererType = Class.forName("java.util.stream.Gatherer");
        Class<?> integratorType = Class.forName("java.util.stream.Gatherer$Integrator");
        Class<?> greedyType = Class.forName("java.util.stream.Gatherer$Integrator$Greedy");
        Method push = Class.forName("java.util.stream.Gatherer$Downstream").getMethod("push", Object.class);

        InvocationHandler integrate = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "integrator";
                };
            }
            @SuppressWarnings("unchecked")
            Collection<Object> state = (Collection<Object>) args[0];
            Object element = args[1];
            integration.around(state, Thread.currentThread(), () -> state.add(element));
            return Boolean.TRUE;
        };
        // Greedy: the integrator never short-circuits, which lets the JDK run the upstream of
        // every segment in parallel ahead of the in-order integration.
        Object integrator = Proxy.newProxyInstance(GathererConcurrencyMisuseDetectorTest.class.getClassLoader(),
            new Class<?>[] {greedyType}, integrate);
        BinaryOperator<Collection<Object>> combiner = (left, right) -> {
            left.addAll(right);
            return left;
        };
        BiConsumer<Collection<Object>, Object> finisher = (state, downstream) -> {
            for (Object element : state) {
                try {
                    push.invoke(downstream, element);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        return withCombiner
            ? gathererType.getMethod("of", Supplier.class, integratorType, BinaryOperator.class, BiConsumer.class)
                .invoke(null, initializer, integrator, combiner, finisher)
            : gathererType.getMethod("ofSequential", Supplier.class, integratorType, BiConsumer.class)
                .invoke(null, initializer, integrator, finisher);
    }

    /**
     * Runs {@code IntStream.range(0, elements).parallel().gather(gatherer).toList()} in its own
     * four-thread pool and returns the output.
     *
     * <p>The pool keeps the thread count independent of the runner's cores, and the last
     * element's upstream step sleeps 20 ms. That holds the rightmost segment back until its
     * predecessors have integrated, so the JDK integrates it on its own worker, a hand-off between
     * threads. Without the delay one thread often drains the whole chain: measured on JDK 26, 20
     * of 30 runs handed off in a four-thread pool and 11 of 30 in a two-thread one; with it, 30 of
     * 30 in both.
     *
     * @param elements the stream length; the last element is the one whose upstream step waits
     */
    private static List<Object> gather(Object gatherer, int elements) throws Exception {
        Method gather = Stream.class.getMethod("gather", Class.forName("java.util.stream.Gatherer"));
        try (ForkJoinPool pool = new ForkJoinPool(4)) {
            List<?> output = pool.submit(() -> {
                Stream<Integer> upstream = IntStream.range(0, elements).boxed().parallel().map(i -> {
                    if (i == elements - 1) {
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
            return new ArrayList<>(output);
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

    // ---- Integration enter and exit: overlap, not thread identity (#846) ----
    //
    // A gatherer with no combiner hands its one state between threads by design (#777), so which
    // thread integrates a state says nothing. Two integrations of one state that are in progress
    // at the same moment on two threads is what the JDK never does, for any gatherer.

    @Test
    void oneStateInsideTwoIntegrationsAtOnceFiresEvenWithoutACombiner() throws Exception {
        // Several streams, each sequential, whose initializer returns the same captured object.
        detector.registerGatherer("g", /* hasCombiner */ false, /* parallel */ false);
        List<String> shared = new ArrayList<>();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            detector.recordIntegrateEnter("g", shared, Thread.currentThread());
            firstInside.countDown();
            try {
                secondInside.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordIntegrateExit("g", shared);
            }
        });
        Thread second = new Thread(() -> {
            try {
                firstInside.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            detector.recordIntegrateEnter("g", shared, Thread.currentThread());
            secondInside.countDown();
            detector.recordIntegrateExit("g", shared);
        });
        first.start();
        second.start();
        first.join();
        second.join();

        var report = detector.analyze();
        assertFalse(report.getSharedStateIssues().isEmpty(),
            "Two threads were inside an integration of one state at once: " + report);
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report.toString()), report.toString());
        assertTrue(report.getMissingCombinerIssues().isEmpty(),
            "A gatherer used on sequential streams needs no combiner: " + report);
    }

    @Test
    void oneStateHandedBetweenThreadsOneIntegrationAtATimeIsNotShared() throws Exception {
        // The JDK's own shape for a combiner-less gatherer on a parallel stream (#777): one state,
        // integrated on one thread, then on another, never both at once.
        detector.registerGatherer("g", /* hasCombiner */ false, /* parallel */ true);
        List<String> state = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                detector.recordIntegrateEnter("g", state, Thread.currentThread());
                detector.recordIntegrateExit("g", state);
            });
            worker.start();
            worker.join();
        }

        var report = detector.analyze();
        assertTrue(report.getSharedStateIssues().isEmpty(),
            "A state handed from thread to thread, one integration at a time, is not shared: " + report);
        assertFalse(report.getMissingCombinerIssues().isEmpty(),
            "The missed-speedup finding is unchanged by the enter/exit records: " + report);
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report.toString()), report.toString());
    }

    @Test
    void twoParallelStreamsSharingOneStateThroughTheInitializerFire() throws Exception {
        assumeGathererIsFinalApi();
        detector.registerGatherer("shared", /* hasCombiner */ false, /* parallel */ true);
        GathererConcurrencyMisuseDetector threeArgumentOnly = new GathererConcurrencyMisuseDetector();
        threeArgumentOnly.registerGatherer("shared", false, true);

        // A thread-safe queue, so the race shows as wrong output rather than a crash inside
        // ArrayList. The first two integrations meet at a barrier while inside: each stream
        // integrates in order, one call at a time, so the two parties are the two streams.
        Collection<Object> shared = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.CyclicBarrier meet = new java.util.concurrent.CyclicBarrier(2);
        AtomicInteger arrivals = new AtomicInteger();
        Object gatherer = newGatherer(false, () -> shared, (state, self, body) -> {
            threeArgumentOnly.recordIntegrate("shared", state, self);
            detector.recordIntegrateEnter("shared", state, self);
            try {
                if (arrivals.getAndIncrement() < 2) {
                    meet.await(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                body.run();
            } finally {
                detector.recordIntegrateExit("shared", state);
            }
        });
        int elements = 2_000;
        var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> gatherQuietly(gatherer, elements));
        var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> gatherQuietly(gatherer, elements));
        int largest = Math.max(one.get().size(), two.get().size());

        assertTrue(largest > elements,
            "The race is real: a stream emitted the other stream's elements (largest output "
                + largest + " of " + elements + " given)");
        assertTrue(threeArgumentOnly.analyze().getSharedStateIssues().isEmpty(),
            "Premise: the three-argument overload skips a gatherer with no combiner, so this "
                + "sharing was invisible to it: " + threeArgumentOnly.analyze());
        var report = detector.analyze();
        assertFalse(report.getSharedStateIssues().isEmpty(),
            "Two streams integrated one state at the same time on two threads: " + report);
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report.toString()), report.toString());
    }

    @Test
    void oneParallelStreamHandingItsStateBetweenThreadsStaysSilentOnEnterAndExit() throws Exception {
        assumeGathererIsFinalApi();

        GathererConcurrencyMisuseDetector fed = null;
        GatherRun run = null;
        for (int attempt = 0; attempt < 5 && (run == null || run.integratingThreads() < 2); attempt++) {
            GathererConcurrencyMisuseDetector current = new GathererConcurrencyMisuseDetector();
            current.registerGatherer("running", /* hasCombiner */ false, /* parallel */ true);
            fed = current;
            run = gatherInParallel(false, (state, self, body) -> {
                current.recordIntegrateEnter("running", state, self);
                try {
                    body.run();
                } finally {
                    current.recordIntegrateExit("running", state);
                }
            });
        }
        assumeTrue(run.integratingThreads() >= 2,
            "The JDK kept every integration on one thread in 5 runs, so there was no hand-off to judge");

        var report = fed.analyze();
        assertEquals(inOrder(), run.output(), "The JDK lost nothing in the run the detector judged");
        assertFalse(run.overlapped(), "Premise: the JDK never integrated the one state twice at once");
        assertTrue(report.getSharedStateIssues().isEmpty(),
            "The JDK handed one state between " + run.integratingThreads() + " threads, one "
                + "integration at a time; that is its design, not sharing: " + report);
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report.toString()),
            "Only the unchanged missed-speedup finding remains: " + report);
    }

    private static List<Object> gatherQuietly(Object gatherer, int elements) {
        try {
            return gather(gatherer, elements);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * #846: an integration whose exit was never recorded (the enter was not paired in a finally)
     * stays open. Rounds run one after another, so one still open when the next round starts is a
     * missing exit, not a concurrent integration, and another thread's enter in the new round must
     * not read as an overlap with it.
     */
    @Test
    void anIntegrationLeftOpenByTheLastRoundIsNotAnOverlapInTheNext() throws Exception {
        detector.registerGatherer("g", false, false); // the overlap rule holds for any shape
        Object state = new Object();
        Thread earlier = new Thread(() -> detector.recordIntegrateEnter("g", state, Thread.currentThread()));
        earlier.start();
        earlier.join();

        detector.markInvocationStart();
        detector.recordIntegrateEnter("g", state, Thread.currentThread());
        detector.recordIntegrateExit("g", state);

        assertFalse(detector.analyze().hasIssues(),
                "the open entry was the previous round's missing exit: " + detector.analyze());
    }

    @Test
    void twoIntegrationsOfOneStateOpenInTheSameRoundStillFire() throws Exception {
        detector.registerGatherer("g", false, false); // the overlap rule holds for any shape
        Object state = new Object();
        detector.markInvocationStart();
        Thread other = new Thread(() -> detector.recordIntegrateEnter("g", state, Thread.currentThread()));
        other.start();
        other.join();

        detector.recordIntegrateEnter("g", state, Thread.currentThread());

        assertTrue(detector.analyze().hasIssues(),
                "within one round an open integration on another thread is the overlap: " + detector.analyze());
    }
}
