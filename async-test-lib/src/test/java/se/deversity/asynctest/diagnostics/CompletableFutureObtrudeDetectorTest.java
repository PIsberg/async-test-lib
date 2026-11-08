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

    /** The detector releases a subject the code under test dropped (#918). */
    @Test
    void doesNotKeepARecordedSubjectAlive() throws InterruptedException {
        CompletableFutureObtrudeDetector d = new CompletableFutureObtrudeDetector();
        AbstractInstanceDetectorTest.assertNotRetained(CompletableFuture<Void>::new, f -> d.recordObtrude(f, null, Thread.currentThread()));
    }

    /**
     * A recordObtrude still under way has registered the future but not yet set its thread or
     * count. analyze() put that null thread into Map.of and threw, so a worker still recording
     * dropped the run's whole report (DetectorAnalysisUnderConcurrentWritesTest, #1212). This
     * builds the half-recorded state directly instead of waiting for the scheduler to.
     */
    @Test
    void aFutureRegisteredButNotYetRecordedIsNotReported() {
        CompletableFutureObtrudeDetector d = new CompletableFutureObtrudeDetector();
        d.stateFor(new CompletableFuture<String>(), "mid-record", "CompletableFuture");

        CompletableFutureObtrudeDetector.Report report = assertDoesNotThrow(d::analyze);

        assertFalse(report.hasIssues());
        assertTrue(report.structuredViolations.isEmpty());
    }
}
