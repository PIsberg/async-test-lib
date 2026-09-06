package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SynchronizedNonFinalDetector}.
 */
public class SynchronizedNonFinalDetectorTest {

    @Test
    void withoutAnOwnerTheReportDoesNotClaimTheLockIsNonFinal() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        // The three-argument form, which is what every existing caller uses. Four workers each
        // with their own final lock are indistinguishable from one reassigned field here, so the
        // finding stands - but it must not assert which, and it used to assert NOT FINAL (#501).
        for (int i = 0; i < 4; i++) {
            detector.recordLockObject(new Object(), "lock", Object.class);
        }

        java.util.List<String> violations = detector.analyze().violations;
        assertEquals(1, violations.size(), "the ambiguity is still worth reporting");
        assertFalse(violations.get(0).contains("NOT FINAL"),
            "this recording cannot tell a reassigned field from four instances each with their "
                + "own final lock, so it must not claim the first: " + violations.get(0));
        assertTrue(violations.get(0).contains("recordLockObject"),
            "and it should say how to have that decided: " + violations.get(0));
    }

    @Test
    void perInstanceFinalLocksAreNotClaimedToBeNonFinal() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        // Four workers, each with its own service object, each holding its own final lock. That
        // is correct code: every instance guards itself. Keyed by class and field name alone the
        // four monitors land in one slot and look like one field reassigned four times, and the
        // report asserted "lock reference is NOT FINAL" - a fact the detector cannot know (#501).
        for (int i = 0; i < 4; i++) {
            Object owner = new Object();
            Object perInstanceFinalLock = new Object();
            detector.recordLockObject(perInstanceFinalLock, "lock", Object.class, owner);
        }

        assertTrue(detector.analyze().violations.isEmpty(),
            "each instance has its own final lock: " + detector.analyze().violations);
    }

    @Test
    void oneInstanceReassigningItsLockIsStillReported() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        Object owner = new Object();

        // The twin: one object, two different monitors, which is the reassignment the detector
        // exists for and the only shape that justifies the NOT FINAL wording.
        detector.recordLockObject(new Object(), "lock", Object.class, owner);
        detector.recordLockObject(new Object(), "lock", Object.class, owner);

        assertFalse(detector.analyze().violations.isEmpty(),
            "one instance synchronized on two objects is a reassigned lock");
        assertTrue(detector.analyze().violations.get(0).contains("NOT FINAL"),
            "and only here is that claim earned: " + detector.analyze().violations);
    }

    @Test
    void testSingleObjectNoIssues() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        Object lock = new Object();

        // Same object instance recorded multiple times → no reassignment
        for (int i = 0; i < 5; i++) {
            detector.recordLockObject(lock, "lock", MyService.class);
        }

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Same object every time should not be flagged");
    }

    @Test
    void testDifferentObjectInstancesDetectsReassignment() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        // First invocation uses one object
        detector.recordLockObject(new Object(), "lock", MyService.class);
        // Second invocation uses a different object — field was reassigned!
        detector.recordLockObject(new Object(), "lock", MyService.class);

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Different objects for same lock slot should be flagged");
        assertFalse(report.violations.isEmpty());
        assertTrue(report.violations.get(0).contains("MyService.lock"));
    }

    @Test
    void testMultipleFieldsTrackedIndependently() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        Object finalLock    = new Object();
        // nonFinalLock will change
        Object lock1 = new Object();
        Object lock2 = new Object();

        detector.recordLockObject(finalLock, "finalLock", MyService.class);
        detector.recordLockObject(finalLock, "finalLock", MyService.class);

        detector.recordLockObject(lock1, "nonFinalLock", MyService.class);
        detector.recordLockObject(lock2, "nonFinalLock", MyService.class);

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Non-final lock should be flagged");
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("nonFinalLock")));
        assertFalse(report.violations.stream().anyMatch(v -> v.contains("finalLock")),
                "Final lock (same object) should not be flagged");
    }

    @Test
    void testNullLockObjectIsIgnored() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        assertDoesNotThrow(() -> detector.recordLockObject(null, "nullLock", MyService.class));

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void testNullFieldIdIsIgnored() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        assertDoesNotThrow(() -> detector.recordLockObject(new Object(), null, MyService.class));

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void testNullOwnerClassUsesFieldIdOnly() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        detector.recordLockObject(new Object(), "myLock", null);
        detector.recordLockObject(new Object(), "myLock", null);

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Null owner class should still track the field by name");
        assertTrue(report.violations.get(0).contains("myLock"));
    }

    @Test
    void testReportToStringContainsKeywords() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        detector.recordLockObject(new Object(), "badLock", MyService.class);
        detector.recordLockObject(new Object(), "badLock", MyService.class);

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("SYNCHRONIZED-ON-NON-FINAL"), "Should contain header");
        assertTrue(text.contains("Fix:"), "Should suggest a fix");
        assertTrue(text.contains("final"), "Should mention 'final'");
    }

    private static class MyService {}
}
