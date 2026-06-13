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
}
