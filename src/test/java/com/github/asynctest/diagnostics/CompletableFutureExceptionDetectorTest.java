package com.github.asynctest.diagnostics;

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
}
