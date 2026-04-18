package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SynchronizedNonFinalDetector}.
 */
public class SynchronizedNonFinalDetectorTest {

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
