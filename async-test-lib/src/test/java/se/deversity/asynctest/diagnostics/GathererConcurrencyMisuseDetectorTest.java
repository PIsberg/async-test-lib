package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

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
    void emitsSharedStateWarning_forParallelMultiThread() throws Exception {
        detector.registerGatherer("g", true, true);
        integrateOnTwoThreads("g");

        var report = detector.analyze();
        assertFalse(report.getSharedStateIssues().isEmpty(),
            "Concurrent integration should prompt a state-confinement check");
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
        assertTrue(str.contains("HIGH"), str);
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
            "Two threads integrated one parallel gatherer that declares no combiner - the "
            + "per-thread states cannot be merged, which is the whole finding. Silence here "
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
}
