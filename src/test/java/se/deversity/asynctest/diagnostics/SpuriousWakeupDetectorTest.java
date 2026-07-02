package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpuriousWakeupDetectorTest {

    @Test
    void cleanWhenWaitInsideLoop() {
        var d = new SpuriousWakeupDetector();
        var lock = new Object();
        d.recordWait(lock, "my-lock", true, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void hazardWhenWaitOutsideLoop() {
        var d = new SpuriousWakeupDetector();
        var lock = new Object();
        d.recordWait(lock, "my-lock", false, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("my-lock"));
        assertTrue(msg.contains("outside a condition-checking loop"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SpuriousWakeup", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void nullMonitorAndThreadAreIgnored() {
        var d = new SpuriousWakeupDetector();
        var lock = new Object();
        d.recordWait(null, "my-lock", false, Thread.currentThread());
        d.recordWait(lock, "my-lock", false, null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingMonitorNameFallsBackToIdentity() {
        var d = new SpuriousWakeupDetector();
        var lock = new Object();
        d.recordWait(lock, null, false, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Monitor@"));
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new SpuriousWakeupDetector().analyze();
        assertEquals("SPURIOUS WAKEUP HAZARD — clean", clean.toString());

        var d = new SpuriousWakeupDetector();
        var lock = new Object();
        d.recordWait(lock, "my-lock", false, Thread.currentThread());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("SPURIOUS WAKEUP HAZARD DETECTED"));
        assertTrue(rendered.contains("my-lock"));
    }
}
