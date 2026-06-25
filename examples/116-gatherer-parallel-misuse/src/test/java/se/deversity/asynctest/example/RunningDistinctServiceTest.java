package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector;
import se.deversity.asynctest.example.service.RunningDistinctService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RunningDistinctService.
 *
 * ========================================================================
 * DETECTOR: GathererConcurrencyMisuseDetector  (standalone — JDK 24+, JEP 485)
 * ========================================================================
 *
 * On a PARALLEL stream the runtime splits the input, runs a Gatherer's integrator on
 * independent per-thread state, then merges those states with the COMBINER. A stateful
 * gatherer with NO combiner (or one whose integrator touches shared state) silently loses
 * or corrupts results.
 *
 * GathererConcurrencyMisuseDetector is standalone (not wired into @AsyncTest). You declare
 * the gatherer's shape up front with registerGatherer(name, hasCombiner, parallel), then
 * call recordIntegrate(name, thread) from the integrator. Once the integrator is observed
 * on more than one thread without a combiner, the detector fires.
 */
class RunningDistinctServiceTest {

    private RunningDistinctService service;
    private GathererConcurrencyMisuseDetector detector;

    @BeforeEach
    void setUp() {
        service = new RunningDistinctService();
        detector = new GathererConcurrencyMisuseDetector();
    }

    /** Run an integrator for {@code name} on two distinct threads (deterministic split). */
    private void integrateOnTwoThreads(String name) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable r = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { return; }
            detector.recordIntegrate(name, Thread.currentThread());
        };
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        ready.await();
        go.countDown();
        a.join(); b.join();
    }

    // -----------------------------------------------------------------------
    // Part 1: safe variant — no shared mutable state; combiner present.
    // -----------------------------------------------------------------------

    @Test
    void safeGatherer_withCombiner_isClean() throws Exception {
        detector.registerGatherer("running-distinct", /*hasCombiner*/ true, /*parallel*/ true);
        integrateOnTwoThreads("running-distinct");

        // The service's safe variant produces correct distinct output.
        List<String> out = service.distinctParallelSafe(List.of("a", "b", "a", "c", "b"));
        assertEquals(3, out.size());

        assertTrue(detector.analyze().getMissingCombinerIssues().isEmpty(),
            "A combiner makes parallel use safe");
    }

    // -----------------------------------------------------------------------
    // Part 2: buggy variant — stateful, no combiner, on a parallel stream.
    // -----------------------------------------------------------------------

    @Test
    void statefulGatherer_noCombiner_onParallelStream_isDetected() throws Exception {
        detector.registerGatherer("running-distinct", /*hasCombiner*/ false, /*parallel*/ true);
        integrateOnTwoThreads("running-distinct");

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getMissingCombinerIssues().isEmpty());
        assertTrue(report.getMissingCombinerIssues().get(0).contains("combiner"));
    }
}
