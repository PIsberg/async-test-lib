package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CompletableFutureExceptionDetector.
 */
public class CompletableFutureExceptionDetectorTest {

    @Test
    void testNormalCompletableFutureUsage() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = CompletableFuture.completedFuture("result");
        
        detector.recordFutureCreated(future, "normal-task");
        detector.recordExceptionHandled(future, "normal-task", null);
        detector.recordFutureCompleted(future, "normal-task", true);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testUnhandledExceptionDetection() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        
        detector.recordFutureCreated(future, "unhandled-task");
        future.completeExceptionally(new RuntimeException("test error"));
        detector.recordFutureCompleted(future, "unhandled-task", false);
        // No exception handler registered!
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect unhandled exception");
        assertFalse(report.unhandledExceptions.isEmpty(), "Should report unhandled exceptions");
    }

    @Test
    void testMissingHandlerDetection() throws InterruptedException {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        
        detector.recordFutureCreated(future, "missing-handler-task");
        // No handler registered, future never completes
        
        // Wait a bit for age check
        Thread.sleep(150);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect missing handler");
        assertFalse(report.missingHandlers.isEmpty(), "Should report missing handlers");
    }

    @Test
    void testGetJoinTracking() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = CompletableFuture.completedFuture("result");
        
        detector.recordFutureCreated(future, "get-join-task");
        detector.recordGetJoinCall(future, "get-join-task", false);
        detector.recordFutureCompleted(future, "get-join-task", true);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        // Should not report issues for normal get/join
        assertTrue(report.unhandledExceptions.isEmpty(), "Should not report unhandled exceptions");
    }

    @Test
    void testExceptionallyHandlerTracking() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        
        detector.recordFutureCreated(future, "exceptionally-task");
        
        // Simulate exceptionally handler
        future.exceptionally(ex -> {
            detector.recordExceptionHandled(future, "exceptionally-task", ex);
            return "default";
        });
        
        future.completeExceptionally(new RuntimeException("error"));
        detector.recordFutureCompleted(future, "exceptionally-task", false);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Handler was registered, should not report issues");
    }

    @Test
    void testNullSafety() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        
        // Should not throw on null inputs
        detector.recordFutureCreated(null, "null-future");
        detector.recordExceptionHandled(null, "null", null);
        detector.recordFutureCompleted(null, "null", true);
        detector.recordGetJoinCall(null, "null", false);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        
        detector.recordFutureCreated(future, "test-task");
        future.completeExceptionally(new RuntimeException("error"));
        detector.recordFutureCompleted(future, "test-task", false);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("COMPLETABLEFUTURE EXCEPTION ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Unhandled Exceptions"), "Report should mention unhandled exceptions");
    }

    @Test
    void testCompletionStatusTracking() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("success");
        CompletableFuture<String> future2 = new CompletableFuture<>();
        
        detector.recordFutureCreated(future1, "success-task");
        detector.recordFutureCompleted(future1, "success-task", true);
        
        detector.recordFutureCreated(future2, "failure-task");
        future2.completeExceptionally(new RuntimeException("error"));
        detector.recordFutureCompleted(future2, "failure-task", false);
        
        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();
        
        assertNotNull(report);
        assertEquals("normal", report.completionStatus.get("success-task"));
        assertEquals("exceptional", report.completionStatus.get("failure-task"));
    }

    @Test
    void testMissingHandlerNotReportedForFreshFuture() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();

        detector.recordFutureCreated(future, "fresh-task");
        // No sleep: real age is a few nanoseconds, well under the 100ms threshold

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.missingHandlers.isEmpty(),
            "Freshly created future should not yet be reported as missing a handler");
    }

    @Test
    void testSwallowedExceptionNotReportedWhenNoGetJoinCalls() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();

        detector.recordFutureCreated(future, "no-get-join-task");
        future.completeExceptionally(new RuntimeException("boom"));
        detector.recordFutureCompleted(future, "no-get-join-task", false);
        // recordGetJoinCall is never invoked, so getJoinCalls stays at 0

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.swallowedExceptions.isEmpty(),
            "Should not report a swallowed exception when get()/join() was never called");
    }

    @Test
    void testSwallowedExceptionNotReportedWhenCompletedNormally() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = CompletableFuture.completedFuture("ok");

        detector.recordFutureCreated(future, "normal-get-join-task");
        detector.recordGetJoinCall(future, "normal-get-join-task", false);
        detector.recordFutureCompleted(future, "normal-get-join-task", true);

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.swallowedExceptions.isEmpty(),
            "Should not report a swallowed exception when the future completed normally");
    }

    @Test
    void testSwallowedExceptionReportedWhenGetJoinCalledOnUncaughtException() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();

        detector.recordFutureCreated(future, "swallowed-task");
        future.completeExceptionally(new RuntimeException("boom"));
        detector.recordGetJoinCall(future, "swallowed-task", true);
        detector.recordFutureCompleted(future, "swallowed-task", false);
        // No recordExceptionHandled call: lastException stays null

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.swallowedExceptions.isEmpty(),
            "Should report a swallowed exception when get/join was called but no handler captured it");
        assertTrue(report.swallowedExceptions.get(0).contains("swallowed-task"));
    }

    @Test
    void testGetJoinCallThrewExceptionMarksCompletedExceptionally() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();

        detector.recordFutureCreated(future, "threw-task");
        detector.recordGetJoinCall(future, "threw-task", true);
        // completedExceptionally is set directly here, independent of recordFutureCompleted

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.swallowedExceptions.isEmpty(),
            "get/join throwing should mark the future as completed exceptionally");
    }

    @Test
    void testGetJoinCallNoExceptionDoesNotMarkCompletedExceptionally() {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();

        detector.recordFutureCreated(future, "no-throw-task");
        detector.recordGetJoinCall(future, "no-throw-task", false);

        CompletableFutureExceptionDetector.CompletableFutureExceptionReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.swallowedExceptions.isEmpty(),
            "get/join not throwing should leave completedExceptionally false");
    }

    @Test
    void testReportToStringMissingHandlersSectionAppearsOnlyWhenPresent() throws InterruptedException {
        CompletableFutureExceptionDetector detectorWithMissing = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detectorWithMissing.recordFutureCreated(future, "missing-handler-tostring-task");
        Thread.sleep(150);
        String withMissing = detectorWithMissing.analyze().toString();
        assertTrue(withMissing.contains("Missing Exception Handlers"),
            "Section should appear when missing handlers are present");

        CompletableFutureExceptionDetector detectorWithout = new CompletableFutureExceptionDetector();
        String without = detectorWithout.analyze().toString();
        assertFalse(without.contains("Missing Exception Handlers"),
            "Section should be absent when there are no missing handlers");
    }

    @Test
    void testReportToStringSwallowedExceptionsSectionAppearsOnlyWhenPresent() {
        CompletableFutureExceptionDetector detectorWithSwallowed = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detectorWithSwallowed.recordFutureCreated(future, "swallowed-tostring-task");
        future.completeExceptionally(new RuntimeException("boom"));
        detectorWithSwallowed.recordGetJoinCall(future, "swallowed-tostring-task", true);
        detectorWithSwallowed.recordFutureCompleted(future, "swallowed-tostring-task", false);
        String withSwallowed = detectorWithSwallowed.analyze().toString();
        assertTrue(withSwallowed.contains("Swallowed Exceptions"),
            "Section should appear when a swallowed exception is present");

        CompletableFutureExceptionDetector detectorWithout = new CompletableFutureExceptionDetector();
        String without = detectorWithout.analyze().toString();
        assertFalse(without.contains("Swallowed Exceptions"),
            "Section should be absent when there are no swallowed exceptions");
    }

    @Test
    void testReportToStringCompletionStatusSectionAppearsOnlyWhenPresent() {
        CompletableFutureExceptionDetector detectorWithCompletion = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = CompletableFuture.completedFuture("done");
        detectorWithCompletion.recordFutureCreated(future, "completion-tostring-task");
        detectorWithCompletion.recordFutureCompleted(future, "completion-tostring-task", true);
        String withCompletion = detectorWithCompletion.analyze().toString();
        assertTrue(withCompletion.contains("Completion Status"),
            "Section should appear once a future has completed");

        CompletableFutureExceptionDetector detectorWithout = new CompletableFutureExceptionDetector();
        String without = detectorWithout.analyze().toString();
        assertFalse(without.contains("Completion Status"),
            "Section should be absent when no future has completed");
    }

    @Test
    void testReportToStringNoIssuesMessageOnlyWhenNoIssues() {
        CompletableFutureExceptionDetector cleanDetector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = CompletableFuture.completedFuture("ok");
        cleanDetector.recordFutureCreated(future, "clean-task");
        cleanDetector.recordFutureCompleted(future, "clean-task", true);
        String clean = cleanDetector.analyze().toString();
        assertTrue(clean.contains("No issues detected"),
            "Should show the no-issues message when nothing was detected");

        CompletableFutureExceptionDetector dirtyDetector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> badFuture = new CompletableFuture<>();
        dirtyDetector.recordFutureCreated(badFuture, "dirty-task");
        badFuture.completeExceptionally(new RuntimeException("bad"));
        dirtyDetector.recordFutureCompleted(badFuture, "dirty-task", false);
        String dirty = dirtyDetector.analyze().toString();
        assertFalse(dirty.contains("No issues detected"),
            "Should not show the no-issues message when issues exist");
    }
}
