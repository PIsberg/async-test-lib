package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompletableFutureChainDetector}.
 */
class CompletableFutureChainDetectorTest {

    private CompletableFutureChainDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CompletableFutureChainDetector();
    }

    @Test
    void testNoIssuesWhenProperlyJoinedAndHandled() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        
        detector.recordFutureCreated(future, "completed-future");
        detector.recordExceptionally(future);
        detector.recordFutureJoined(future, "completed-future");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
        assertEquals(1, report.totalCreated);
        assertEquals(1, report.totalJoined);
    }

    @Test
    void testDetectsUnjoinedFutures() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        
        detector.recordFutureCreated(future, "unjoined-future");
        // Missing: recordFutureJoined

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.unjoinedFutures.isEmpty());
        assertTrue(report.unjoinedFutures.get(0).contains("unjoined-future"));
    }

    @Test
    void testDetectsMissingExceptionHandler() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());
        
        detector.recordFutureCreated(future, "unhandled-chain");
        detector.recordChainOperation(future, chained, "thenApply");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.missingExceptionHandler.isEmpty());
        assertTrue(report.missingExceptionHandler.get(0).contains("thenApply"));
    }

    @Test
    void testNoIssueWhenExceptionallyAdded() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());
        
        detector.recordFutureCreated(future, "handled-chain");
        detector.recordChainOperation(future, chained, "thenApply");
        detector.recordExceptionally(future);

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testNoIssueWhenHandleAdded() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        CompletableFuture<String> chained = future.thenApply(s -> s.toUpperCase());
        
        detector.recordFutureCreated(future, "handled-chain");
        detector.recordChainOperation(future, chained, "thenApply");
        detector.recordHandle(future);

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testDisabledDetectorReturnsNoIssues() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        
        detector.disable();
        detector.recordFutureCreated(future, "disabled-future");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testMultipleChainsTracked() {
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("test1");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("test2");
        CompletableFuture<String> chained1 = future1.thenApply(s -> s.toUpperCase());
        CompletableFuture<String> chained2 = future2.thenApply(s -> s.toLowerCase());
        
        detector.recordFutureCreated(future1, "chain-1");
        detector.recordFutureCreated(future2, "chain-2");
        detector.recordChainOperation(future1, chained1, "thenApply");
        detector.recordChainOperation(future2, chained2, "thenApply");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.missingExceptionHandler.isEmpty());
    }

    @Test
    void testDetectsUnusedFutures() {
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("test1");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("test2");
        
        detector.recordFutureCreated(future1, "joined-future");
        detector.recordFutureCreated(future2, "unused-future");
        detector.recordFutureJoined(future1, "joined-future");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.unusedFutures.isEmpty());
        assertTrue(report.unusedFutures.get(0).contains("1 futures created but never joined"));
    }

    @Test
    void testReportToStringContainsIssues() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("test");
        
        detector.recordFutureCreated(future, "failing-future");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("COMPLETABLEFUTURE CHAIN ISSUES DETECTED"));
        assertTrue(reportStr.contains("Unjoined Futures"));
    }

    @Test
    void testNullInputsAreIgnored() {
        assertDoesNotThrow(() -> {
            detector.recordFutureCreated(null, "test");
            detector.recordChainOperation(null, null, "thenApply");
            detector.recordExceptionally(null);
            detector.recordHandle(null);
            detector.recordFutureJoined(null, "test");
        });
    }

    @Test
    void testSummaryStatisticsAreCorrect() {
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("test1");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("test2");
        CompletableFuture<String> chained = future1.thenApply(s -> s.toUpperCase());
        
        detector.recordFutureCreated(future1, "future-1");
        detector.recordFutureCreated(future2, "future-2");
        detector.recordChainOperation(future1, chained, "thenApply");
        detector.recordFutureJoined(future1, "future-1");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        assertEquals(2, report.totalCreated);
        assertEquals(1, report.totalJoined);
        assertEquals(1, report.totalChained);
    }

    @Test
    void testEnableDisableLifecycle() {
        CompletableFuture<String> future1 = CompletableFuture.completedFuture("test1");
        CompletableFuture<String> future2 = CompletableFuture.completedFuture("test2");
        
        detector.recordFutureCreated(future1, "future-1");
        detector.disable();
        
        detector.recordFutureCreated(future2, "future-2");
        
        detector.enable();
        detector.recordFutureJoined(future1, "future-1");

        CompletableFutureChainDetector.CompletableFutureChainReport report = detector.analyze();

        // future-2 was not recorded (disabled), so only future-1 exists and is joined
        assertFalse(report.hasIssues());
    }
}
