package se.deversity.asynctest.fixture;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.GathererConcurrencyMisuseDetector;
import se.deversity.asynctest.diagnostics.StableValueMisuseDetector;
import se.deversity.asynctest.diagnostics.StructuredTaskScopeMisuseDetector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-side smoke tests for the standalone JDK 25/26 detectors added in 1.7.0:
 * {@code StableValueMisuseDetector}, {@code StructuredTaskScopeMisuseDetector}, and
 * {@code GathererConcurrencyMisuseDetector}.
 *
 * <p>These compile against the published artifact (consumer-fixture depends on the deployed
 * JAR, not the source), so a passing run proves the three detector classes — and their
 * record/analyze API and report accessors — are part of the stable public surface a
 * consumer can reach without depending on internal classes.
 *
 * <p>Unlike the pipeline detectors, these are <strong>not</strong> wired into
 * {@code @AsyncTest} (no {@code DetectorType} constant — that enum is locked). They are used
 * directly: instantiate, record events, call {@code analyze()}.
 */
class ConsumerJdk25And26DetectorsTest {

    // ---- StableValueMisuseDetector (JEP 502) ----

    @Test
    void stableValue_detector_public_api_flags_read_before_set() {
        var d = new StableValueMisuseDetector();
        Thread t = Thread.currentThread();

        d.recordRead("CONFIG", t);                 // read before any set
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getReadBeforeSetIssues().isEmpty());
        assertEquals(1, report.getTotalReads());
    }

    @Test
    void stableValue_detector_clean_when_set_then_read() {
        var d = new StableValueMisuseDetector();
        Thread t = Thread.currentThread();

        d.recordSet("CONFIG", t);
        d.recordRead("CONFIG", t);
        assertFalse(d.analyze().hasIssues());
    }

    // ---- StructuredTaskScopeMisuseDetector (JEP 505) ----

    @Test
    void structuredTaskScope_detector_public_api_flags_fork_after_join() {
        var d = new StructuredTaskScopeMisuseDetector();
        Thread owner = Thread.currentThread();

        d.recordScopeOpened("s", owner);
        d.recordFork("s", "a", owner);
        d.recordJoin("s", owner);
        d.recordFork("s", "late", owner);          // fork after join

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getForkAfterJoinIssues().size());
        assertEquals(1, report.getTotalScopes());
    }

    @Test
    void structuredTaskScope_detector_clean_for_correct_lifecycle() {
        var d = new StructuredTaskScopeMisuseDetector();
        Thread owner = Thread.currentThread();

        d.recordScopeOpened("s", owner);
        d.recordFork("s", "a", owner);
        d.recordJoin("s", owner);
        d.recordResultRead("s", "a", owner);
        d.recordScopeClosed("s", owner);
        assertFalse(d.analyze().hasIssues());
    }

    // ---- GathererConcurrencyMisuseDetector (JEP 485) ----

    @Test
    void gatherer_detector_public_api_flags_missing_combiner_across_threads() throws Exception {
        var d = new GathererConcurrencyMisuseDetector();
        d.registerGatherer("g", /*hasCombiner*/ false, /*parallel*/ true);

        Thread a = new Thread(() -> d.recordIntegrate("g", Thread.currentThread()));
        Thread b = new Thread(() -> d.recordIntegrate("g", Thread.currentThread()));
        a.start(); b.start();
        a.join(); b.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getMissingCombinerIssues().isEmpty());
    }

    @Test
    void gatherer_detector_clean_on_single_thread() {
        var d = new GathererConcurrencyMisuseDetector();
        d.registerGatherer("g", false, false);
        d.recordIntegrate("g", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }
}
