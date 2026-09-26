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

        // The three-argument form. Four workers each with their own final lock are
        // indistinguishable from one reassigned field here. It used to assert NOT FINAL (#501),
        // then reported the ambiguity as a finding; one of the two readings is correct code, so
        // it is a note in the report text and not a finding at all.
        for (int i = 0; i < 4; i++) {
            detector.recordLockObject(new Object(), "lock", Object.class);
        }

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();
        assertTrue(report.violations.isEmpty(), "undecidable is not a finding: " + report);
        assertFalse(report.toString().contains("NOT FINAL"),
            "this recording cannot tell a reassigned field from four instances each with their "
                + "own final lock, so it must not claim the first: " + report);
        assertTrue(report.toString().contains("recordLockObject"),
            "and it should say how to have that decided: " + report);
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

    /** A service guarding itself with its own final lock: correct code. */
    private static final class GuardedService {
        private final Object lock = new Object();
        private int count;

        void increment(SynchronizedNonFinalDetector detector) {
            detector.recordLockObject(lock, "lock", GuardedService.class);
            synchronized (lock) {
                count++;
            }
        }
    }

    @Test
    void perInstanceFinalLocksRecordedWithoutAnOwnerAreNotReported() throws InterruptedException {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        Runnable ownService = () -> {
            GuardedService service = new GuardedService();
            service.increment(detector);
            service.increment(detector);
        };
        Thread a = new Thread(ownService);
        Thread b = new Thread(ownService);
        a.start();
        b.start();
        a.join();
        b.join();

        assertFalse(detector.analyze().hasIssues(),
            "Each thread built its own service, and each service has a private final lock. That "
                + "is correct code, and without the owner it is indistinguishable from one "
                + "reassigned field, so it cannot be a VERDICT finding: " + detector.analyze());
        assertFalse(detector.analyze().toString().contains("Undecided"),
            "GuardedService.lock is declared final, so several monitors can only be several "
                + "instances; the declaration decides it without the owner (#768): "
                + detector.analyze());
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

    /** A static lock field somebody reassigns: one slot per class, so a new monitor is a reassignment. */
    private static final class ReassignedStaticLock {
        private static Object lock = new Object();
    }

    @Test
    void aReassignedStaticLockRecordedWithoutAnOwnerIsReported() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        Object original = ReassignedStaticLock.lock;
        try {
            // The three-argument form, which is all a static field needs: the class is the only
            // owner such a field has, so two monitors on it mean the field was reassigned (#768).
            detector.recordLockObject(ReassignedStaticLock.lock, "lock", ReassignedStaticLock.class);
            ReassignedStaticLock.lock = new Object();
            detector.recordLockObject(ReassignedStaticLock.lock, "lock", ReassignedStaticLock.class);
        } finally {
            ReassignedStaticLock.lock = original;
        }

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();
        assertTrue(report.hasIssues(),
            "a static field has one value per class, so two monitors on it are a reassignment: "
                + report);
        assertTrue(report.violations.get(0).contains("ReassignedStaticLock.lock"), report.toString());
    }

    /** A non-final instance lock field: several monitors are ambiguous without the instance. */
    private static final class NonFinalInstanceLock {
        private Object lock = new Object();
    }

    @Test
    void aNonFinalInstanceFieldWithoutAnOwnerSaysHowToDecideIt() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        for (int i = 0; i < 3; i++) {
            NonFinalInstanceLock holder = new NonFinalInstanceLock();
            detector.recordLockObject(holder.lock, "lock", NonFinalInstanceLock.class);
        }

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();
        assertFalse(report.hasIssues(),
            "three instances that never reassigned their lock record exactly what one reassigned "
                + "field does, so this stays unreported: " + report);
        assertTrue(report.toString().contains("non-final instance field"),
            "the note should say which of the two it could not tell apart: " + report);
        assertTrue(report.toString().contains("recordLockObject(lock, \"lock\", NonFinalInstanceLock.class, this)"),
            "and give the call that decides it, with this slot's own names: " + report);
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

        MyService service = new MyService();
        // First invocation uses one object
        detector.recordLockObject(new Object(), "lock", MyService.class, service);
        // Second invocation uses a different object on the same instance — field was reassigned!
        detector.recordLockObject(new Object(), "lock", MyService.class, service);

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

        MyService service = new MyService();
        detector.recordLockObject(finalLock, "finalLock", MyService.class, service);
        detector.recordLockObject(finalLock, "finalLock", MyService.class, service);

        detector.recordLockObject(lock1, "nonFinalLock", MyService.class, service);
        detector.recordLockObject(lock2, "nonFinalLock", MyService.class, service);

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

        Object owner = new Object();
        detector.recordLockObject(new Object(), "myLock", null, owner);
        detector.recordLockObject(new Object(), "myLock", null, owner);

        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Null owner class should still track the field by name");
        assertTrue(report.violations.get(0).contains("myLock"));
    }

    @Test
    void testReportToStringContainsKeywords() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();

        MyService service = new MyService();
        detector.recordLockObject(new Object(), "badLock", MyService.class, service);
        detector.recordLockObject(new Object(), "badLock", MyService.class, service);

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("SYNCHRONIZED-ON-NON-FINAL"), "Should contain header");
        assertTrue(text.contains("Fix:"), "Should suggest a fix");
        assertTrue(text.contains("final"), "Should mention 'final'");
    }

    @Test
    void twoClassesSharingASimpleNameAreTwoSlots() {
        SynchronizedNonFinalDetector detector = new SynchronizedNonFinalDetector();
        detector.recordLockObject(new Object(), "lock", First.Service.class);
        detector.recordLockObject(new Object(), "lock", Second.Service.class);

        assertFalse(detector.analyze().toString().contains("different objects"),
            "each class used one monitor; keyed by simple name the two read as one slot that "
                + "changed its lock: " + detector.analyze());
    }

    private static final class First {
        private static final class Service { }
    }

    private static final class Second {
        private static final class Service { }
    }

    private static class MyService {}
}
