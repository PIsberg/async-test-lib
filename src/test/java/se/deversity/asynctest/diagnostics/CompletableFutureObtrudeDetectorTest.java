package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class CompletableFutureObtrudeDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new CompletableFutureObtrudeDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void recordObtrudeIsFlagged() {
        var d = new CompletableFutureObtrudeDetector();
        var future = new CompletableFuture<String>();
        d.recordObtrude(future, "my-future", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("my-future"));
        assertTrue(msg.contains("1 times"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("CompletableFutureObtrude", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void nullFutureAndThreadAreIgnored() {
        var d = new CompletableFutureObtrudeDetector();
        var future = new CompletableFuture<String>();
        d.recordObtrude(null, "my-future", Thread.currentThread());
        d.recordObtrude(future, "my-future", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLabelFallsBackToIdentity() {
        var d = new CompletableFutureObtrudeDetector();
        var future = new CompletableFuture<String>();
        d.recordObtrude(future, null, Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("CompletableFuture@"));
    }

    @Test
    void repeatedObtrudeAccumulatesCount() {
        var d = new CompletableFutureObtrudeDetector();
        var future = new CompletableFuture<String>();
        d.recordObtrude(future, "my-future", Thread.currentThread());
        d.recordObtrude(future, "my-future", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.violations.get(0).contains("2 times"));
    }

    @Test
    void reportToStringReflectsState() {
        var clean = new CompletableFutureObtrudeDetector().analyze();
        assertEquals("COMPLETABLE FUTURE OBTRUDE — clean", clean.toString());

        var d = new CompletableFutureObtrudeDetector();
        var future = new CompletableFuture<String>();
        d.recordObtrude(future, "my-future", Thread.currentThread());
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("COMPLETABLE FUTURE OBTRUDE DETECTED"));
        assertTrue(rendered.contains("my-future"));
    }
}
