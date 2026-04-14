package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpClientConcurrencyDetector}.
 */
class HttpClientConcurrencyDetectorTest {

    private HttpClientConcurrencyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new HttpClientConcurrencyDetector();
    }

    @Test
    void testNoIssuesWhenProperlyCompleted() {
        Object client = new Object();
        Object request = new Object();
        Object response = new Object();

        detector.recordClientCreated(client, "test-client");
        detector.recordRequestSent(request, "api-call");
        detector.recordResponseReceived(response, "api-call");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testDetectsPendingRequests() {
        Object client = new Object();
        Object request = new Object();

        detector.recordClientCreated(client, "test-client");
        detector.recordRequestSent(request, "api-call");
        // Missing: recordResponseReceived

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.pendingRequests.isEmpty());
        assertTrue(report.pendingRequests.get(0).contains("api-call"));
    }

    @Test
    void testDetectsUncompletedRequests() {
        Object client = new Object();
        Object request1 = new Object();
        Object request2 = new Object();
        Object response = new Object();

        detector.recordClientCreated(client, "test-client");
        detector.recordRequestSent(request1, "api-call-1");
        detector.recordRequestSent(request2, "api-call-2");
        detector.recordResponseReceived(response, "api-call-1");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.uncompletedRequests.isEmpty());
    }

    @Test
    void testDisabledDetectorReturnsNoIssues() {
        detector.disable();

        Object client = new Object();
        Object request = new Object();

        detector.recordClientCreated(client, "test-client");
        detector.recordRequestSent(request, "api-call");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testMultipleClientsTracked() {
        Object client1 = new Object();
        Object client2 = new Object();
        Object request1 = new Object();
        Object request2 = new Object();

        detector.recordClientCreated(client1, "client-1");
        detector.recordClientCreated(client2, "client-2");
        detector.recordRequestSent(request1, "call-1");
        detector.recordRequestSent(request2, "call-2");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
    }

    @Test
    void testReportToStringContainsIssues() {
        Object request = new Object();

        detector.recordRequestSent(request, "failing-call");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("HTTP CLIENT CONCURRENCY ISSUES DETECTED"));
        assertTrue(reportStr.contains("Pending Requests"));
    }

    @Test
    void testNullInputsAreIgnored() {
        assertDoesNotThrow(() -> {
            detector.recordClientCreated(null, "test");
            detector.recordRequestSent(null, "test");
            detector.recordResponseReceived(null, "test");
        });
    }

    @Test
    void testEnableDisableLifecycle() {
        Object request = new Object();

        detector.recordRequestSent(request, "call-1");
        detector.disable();
        
        Object request2 = new Object();
        detector.recordRequestSent(request2, "call-2");
        
        detector.enable();
        Object request3 = new Object();
        detector.recordRequestSent(request3, "call-3");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();

        // Should only have recorded call-1 and call-3, not call-2
        assertTrue(report.hasIssues());
    }
}
