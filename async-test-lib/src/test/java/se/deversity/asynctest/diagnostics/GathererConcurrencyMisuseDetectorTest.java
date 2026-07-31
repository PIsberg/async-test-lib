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
}
