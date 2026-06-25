package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.StableValueMisuseDetector;
import se.deversity.asynctest.example.service.StableValueConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for StableValueConfigService.
 *
 * ========================================================================
 * DETECTOR: StableValueMisuseDetector  (standalone — JDK 25/26, JEP 502)
 * ========================================================================
 *
 * StableValueMisuseDetector is NOT wired into the @AsyncTest detectAll pipeline
 * (a pipeline detector needs a DetectorType enum constant, and that enum is a
 * locked file). It is used directly: instantiate it, record events around the
 * holder under test, then call analyze() and assert on the report.
 *
 * THE BUG:
 *   - orElseThrow() called before the value was ever set → NoSuchElementException
 *   - setOrThrow() called twice → IllegalStateException / lost update
 *
 * THE FIX:
 *   - Use orElseSet(supplier): lazy, at-most-once, thread-safe, with a pure supplier.
 */
class StableValueConfigServiceTest {

    private StableValueConfigService service;
    private StableValueMisuseDetector detector;

    @BeforeEach
    void setUp() {
        service = new StableValueConfigService();
        detector = new StableValueMisuseDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: correct usage — orElseSet, then read. No misuse.
    // -----------------------------------------------------------------------

    @Test
    void correctUsage_orElseSet_thenRead_isClean() {
        Thread t = Thread.currentThread();

        detector.recordSupplierStart("CONFIG", t);
        String value = service.orElseSet(() -> "db-url=localhost");
        detector.recordSupplierEnd("CONFIG", t);

        detector.recordRead("CONFIG", t);
        assertEquals("db-url=localhost", service.orElseThrow());

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: read before set — NoSuchElementException risk, caught by the detector.
    // -----------------------------------------------------------------------

    @Test
    void readBeforeSet_isDetected() {
        Thread t = Thread.currentThread();

        detector.recordRead("CONFIG", t);                 // BUG: nothing set yet
        assertThrows(java.util.NoSuchElementException.class, () -> service.orElseThrow());

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getReadBeforeSetIssues().isEmpty());
        assertTrue(report.getReadBeforeSetIssues().get(0).contains("NoSuchElementException"));
    }

    // -----------------------------------------------------------------------
    // Part 3: double set — second setOrThrow is a lost update, caught by the detector.
    // -----------------------------------------------------------------------

    @Test
    void doubleSet_isDetected() {
        Thread t = Thread.currentThread();

        detector.recordSet("CONFIG", t);
        service.setOrThrow("first");

        detector.recordSet("CONFIG", t);                  // BUG: second set
        assertThrows(IllegalStateException.class, () -> service.setOrThrow("second"));

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getDoubleSetIssues().size());
        assertEquals("first", service.orElseThrow(), "first writer wins; second value is lost");
    }
}
