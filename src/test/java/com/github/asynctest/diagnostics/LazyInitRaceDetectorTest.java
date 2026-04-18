package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LazyInitRaceDetector}.
 */
public class LazyInitRaceDetectorTest {

    @Test
    void testSingleInitializationNoIssues() {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        detector.recordNullCheck("MyService.instance", true, false);
        detector.recordInitialization("MyService.instance");

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single initialization should not be flagged");
    }

    @Test
    void testSingleThreadNonNullNoIssues() {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        // Field is already set — no initialization needed
        detector.recordNullCheck("MyService.instance", false, false);

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        assertFalse(report.hasIssues(), "Non-null check with no initialization should not be flagged");
    }

    @Test
    void testDuplicateInitializationDetected() throws InterruptedException {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        // Simulate two threads both seeing null and both initializing
        Thread t1 = new Thread(() -> {
            detector.recordNullCheck("Singleton.instance", true, false);
            detector.recordInitialization("Singleton.instance");
        });

        Thread t2 = new Thread(() -> {
            detector.recordNullCheck("Singleton.instance", true, false);
            detector.recordInitialization("Singleton.instance");
        });

        t1.start(); t2.start();
        t1.join();  t2.join();

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Dual initialization from two threads should be detected");
        assertFalse(report.races.isEmpty(), "Should report a race");
        assertTrue(report.races.get(0).contains("Singleton.instance"));
    }

    @Test
    void testNonVolatileMultipleConcurrentNullChecks() throws InterruptedException {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        // Multiple threads see null simultaneously but only one initializes
        Thread t1 = new Thread(() ->
                detector.recordNullCheck("Config.instance", true, false));
        Thread t2 = new Thread(() ->
                detector.recordNullCheck("Config.instance", true, false));
        Thread t3 = new Thread(() ->
                detector.recordNullCheck("Config.instance", true, false));

        t1.start(); t2.start(); t3.start();
        t1.join();  t2.join();  t3.join();

        detector.recordInitialization("Config.instance");

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        // Even though only one initialization happened, the field is not volatile
        // so we report visibility risk
        assertTrue(report.hasIssues(), "Non-volatile field with concurrent null checks should flag visibility risk");
    }

    @Test
    void testVolatileWithSingleInitNoIssues() throws InterruptedException {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        Thread t1 = new Thread(() ->
                detector.recordNullCheck("Registry.instance", true, true));
        Thread t2 = new Thread(() ->
                detector.recordNullCheck("Registry.instance", true, true));

        t1.start(); t2.start();
        t1.join();  t2.join();

        // Only one thread initializes (proper DCL with volatile)
        detector.recordInitialization("Registry.instance");

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        assertFalse(report.hasIssues(),
                "Volatile field with concurrent null checks but single initialization should not be flagged");
    }

    @Test
    void testReportIncludesVolatileNote() throws InterruptedException {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        Thread t1 = new Thread(() -> {
            detector.recordNullCheck("X.obj", true, false);
            detector.recordInitialization("X.obj");
        });
        Thread t2 = new Thread(() -> {
            detector.recordNullCheck("X.obj", true, false);
            detector.recordInitialization("X.obj");
        });

        t1.start(); t2.start();
        t1.join(); t2.join();

        String text = detector.analyze().toString();

        assertTrue(text.contains("NOT volatile"), "Report should note the field is not volatile");
    }

    @Test
    void testMultipleFieldsTrackedIndependently() {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        // Field A: clean single init
        detector.recordNullCheck("A.instance", true, true);
        detector.recordInitialization("A.instance");

        // Field B: double init race (simulated sequentially for determinism)
        detector.recordNullCheck("B.instance", true, false);
        detector.recordInitialization("B.instance");
        detector.recordNullCheck("B.instance", true, false);
        detector.recordInitialization("B.instance");

        LazyInitRaceDetector.LazyInitRaceReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Field B should have issues");
        assertTrue(report.races.stream().anyMatch(r -> r.contains("B.instance")));
        assertFalse(report.races.stream().anyMatch(r -> r.contains("A.instance")),
                "Field A initialized cleanly should not appear in races");
    }

    @Test
    void testNullFieldIdIsIgnored() {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        assertDoesNotThrow(() -> {
            detector.recordNullCheck(null, true, false);
            detector.recordInitialization(null);
        });

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsKeywords() {
        LazyInitRaceDetector detector = new LazyInitRaceDetector();

        // Simulate a race
        detector.recordNullCheck("Z.obj", true, false);
        detector.recordInitialization("Z.obj");
        detector.recordNullCheck("Z.obj", true, false);
        detector.recordInitialization("Z.obj");

        String text = detector.analyze().toString();

        assertNotNull(text);
        assertTrue(text.contains("LAZY INITIALIZATION RACE"), "Should contain header");
        assertTrue(text.contains("Fix:"), "Should suggest a fix");
        assertTrue(text.contains("volatile") || text.contains("holder"), "Should mention solutions");
    }
}
