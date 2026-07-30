package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThisEscapeDetectorTest {

    @Test
    void cleanWhenNoEscape() {
        var d = new ThisEscapeDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void recordedEscapeIsFlaggedAsMediumWhenNotObserved() {
        var d = new ThisEscapeDetector();
        Object instance = new Object();
        d.recordConstructorEscape(instance, "bus.register(this)", Thread.currentThread());
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("bus.register(this)"));
        assertTrue(msg.contains("partially-constructed"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("ThisEscape", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
        assertEquals(false, report.structuredViolations.get(0).attributes().get("observedBeforeComplete"));
    }

    @Test
    void escapeObservedByAnotherThreadBeforeCompletionIsHigh() throws Exception {
        var d = new ThisEscapeDetector();
        Object instance = new Object();
        d.recordConstructorEscape(instance, "new Thread(this::run).start()", Thread.currentThread());
        Thread t = new Thread(() -> d.recordExternalAccess(instance, Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals(true, report.structuredViolations.get(0).attributes().get("observedBeforeComplete"));
        assertTrue(report.violations.get(0).contains("before construction completed"));
    }

    @Test
    void accessAfterCompletionDoesNotEscalate() throws Exception {
        var d = new ThisEscapeDetector();
        Object instance = new Object();
        d.recordConstructorEscape(instance, "register(this)", Thread.currentThread());
        d.recordConstructionComplete(instance);
        Thread t = new Thread(() -> d.recordExternalAccess(instance, Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        // still flagged (escape happened) but not escalated to HIGH
        assertTrue(report.hasIssues());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    @Test
    void externalAccessWithoutRecordedEscapeIsIgnored() throws Exception {
        var d = new ThisEscapeDetector();
        Object instance = new Object();
        Thread t = new Thread(() -> d.recordExternalAccess(instance, Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sameThreadAccessDoesNotEscalate() {
        var d = new ThisEscapeDetector();
        Object instance = new Object();
        d.recordConstructorEscape(instance, "leak", Thread.currentThread());
        d.recordExternalAccess(instance, Thread.currentThread()); // same (constructing) thread
        var report = d.analyze();
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    @Test
    void nullsAreIgnored() {
        var d = new ThisEscapeDetector();
        d.recordConstructorEscape(null, "how", Thread.currentThread());
        d.recordConstructorEscape(new Object(), "how", null);
        d.recordConstructionComplete(null);
        d.recordExternalAccess(null, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }
}
