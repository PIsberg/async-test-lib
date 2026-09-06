package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StableValueMisuseDetector}.
 */
class StableValueMisuseDetectorTest {

    private StableValueMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StableValueMisuseDetector();
    }

    @Test
    void aSupplierThatThrewInAnEarlierRoundDoesNotLookReentrantInTheNext() {
        Thread t = Thread.currentThread();

        // Round one: the supplier throws before recordSupplierEnd, leaving the name in flight.
        detector.recordSupplierStart("CONFIG", t);

        detector.markInvocationStart();

        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);

        assertTrue(detector.analyze().getReentrantIssues().isEmpty(),
            "a supplier that threw in an earlier round left its name in activeSuppliers; the next "
                + "round's supplier on the reused thread is not reentrant: "
                + detector.analyze().getReentrantIssues());
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenSetThenRead() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordRead("CONFIG", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
        assertEquals(1, report.getTotalSets());
        assertEquals(1, report.getTotalReads());
    }

    @Test
    void noIssues_forOrElseSetSupplierThenRead() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);
        detector.recordRead("CONFIG", t); // value is set after supplier completes

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
    }

    // ---- Read before set ----

    @Test
    void detectsReadBeforeSet() {
        detector.recordRead("CONFIG", Thread.currentThread());

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getReadBeforeSetIssues().isEmpty());
        String issue = report.getReadBeforeSetIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("NoSuchElementException"), issue);
    }

    @Test
    void noReadBeforeSet_whenReadHappensAfterSet() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordRead("CONFIG", t);
        detector.recordRead("CONFIG", t);

        assertTrue(detector.analyze().getReadBeforeSetIssues().isEmpty());
    }

    // ---- Double set ----

    @Test
    void detectsDoubleSet() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordSet("CONFIG", t); // second set — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getDoubleSetIssues().size());
        String issue = report.getDoubleSetIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("already set"), issue);
    }

    @Test
    void noDoubleSet_forDistinctHolders() {
        Thread t = Thread.currentThread();
        detector.recordSet("A", t);
        detector.recordSet("B", t);

        assertTrue(detector.analyze().getDoubleSetIssues().isEmpty());
    }

    // ---- Reentrant computation ----

    @Test
    void detectsReentrantSupplier() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierStart("CONFIG", t); // re-entered while computing — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getReentrantIssues().size());
        assertTrue(report.getReentrantIssues().get(0).contains("CONFIG"));
    }

    @Test
    void noReentrancy_whenSupplierEndsBeforeRestart() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);
        detector.recordSupplierStart("CONFIG", t); // sequential, not reentrant
        detector.recordSupplierEnd("CONFIG", t);

        assertTrue(detector.analyze().getReentrantIssues().isEmpty());
    }

    // ---- Set contention ----

    @Test
    void detectsSetContention_whenManyThreadsRaceToSet() throws Exception {
        Runnable r = () -> detector.recordSet("CONFIG", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r), c = new Thread(r);
        a.start(); b.start(); c.start();
        a.join(); b.join(); c.join();

        var report = detector.analyze();
        assertFalse(report.getContentionWarnings().isEmpty(),
            "Three distinct threads racing one holder should warn");
        assertTrue(report.getContentionWarnings().get(0).contains("CONFIG"));
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordSet(null, Thread.currentThread());
            detector.recordSet("K", null);
            detector.recordRead(null, Thread.currentThread());
            detector.recordRead("K", null);
            detector.recordSupplierStart(null, null);
            detector.recordSupplierEnd("K", null);
        });
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("No StableValue misuse"));
    }

    @Test
    void toString_containsLearningContent_whenIssuesFound() {
        detector.recordRead("CONFIG", Thread.currentThread());
        String str = detector.analyze().toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("StableValue"), str);
        assertTrue(str.contains("orElseSet"), str);
    }

    @Test
    void toString_showsCritical_forReadBeforeSet() {
        detector.recordRead("CONFIG", Thread.currentThread());
        assertTrue(detector.analyze().toString().contains("CRITICAL"));
    }

    @Test
    void toString_showsHigh_forDoubleSetOnly() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordSet("CONFIG", t);
        String str = detector.analyze().toString();
        assertTrue(str.contains("HIGH"), str);
    }
}
