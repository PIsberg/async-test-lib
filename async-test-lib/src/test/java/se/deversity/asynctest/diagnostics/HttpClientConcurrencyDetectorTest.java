package se.deversity.asynctest.diagnostics;

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

    /**
     * An {@code HttpRequest} is immutable and made to be sent again, so one request object sent
     * by two threads is two sends. Filed by the request's identity, the second send overwrote the
     * first, the second response found nothing left to answer, and a fully answered run reported
     * a request that never got its response.
     */
    @Test
    void oneRequestObjectSentTwiceAndAnsweredTwiceIsClean() {
        Object client = new Object();
        Object request = new Object();
        detector.recordClientCreated(client, "test-client");

        detector.recordRequestSent(request, "api-call");
        detector.recordRequestSent(request, "api-call");
        detector.recordResponseReceived(new Object(), "api-call");
        detector.recordResponseReceived(new Object(), "api-call");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
        assertFalse(report.hasIssues(), "both sends were answered: " + report);
    }

    /**
     * A request recorded without its client cannot be tied to one of several registered clients.
     * It used to be filed under whichever client the map returned first, so the report blamed a
     * client that may never have sent anything.
     */
    @Test
    void aRequestWithNoClientIsNotBlamedOnAnArbitraryRegisteredClient() {
        detector.recordClientCreated(new Object(), "client-1");
        detector.recordClientCreated(new Object(), "client-2");

        detector.recordRequestSent(new Object(), "unanswered");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
        assertTrue(report.hasIssues(), "the unanswered request is still a finding");
        java.util.List<String> lines = new java.util.ArrayList<>(report.pendingRequests);
        lines.addAll(report.uncompletedRequests);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("client-1") || l.startsWith("client-2")),
                "neither registered client is known to have sent it: " + lines);
    }

    /** With exactly one client registered, a request with no client named is that client's. */
    @Test
    void aRequestWithNoClientBelongsToTheOnlyRegisteredClient() {
        detector.recordClientCreated(new Object(), "only-client");
        detector.recordRequestSent(new Object(), "unanswered");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
        assertTrue(report.uncompletedRequests.get(0).startsWith("only-client:"),
                report.uncompletedRequests.toString());
    }

    /**
     * A request sent through a named client is that client's, even when a second client's
     * identity hash collides with it: keyed by the bare hash, the second registration was dropped
     * and every request of the second client was reported under the first one's name.
     */
    @Test
    void aRequestSentThroughANamedClientIsReportedUnderThatClient() {
        java.util.List<Object> colliding = IdentityCollisions.pair(Object::new);
        detector.recordClientCreated(colliding.get(0), "first");
        detector.recordClientCreated(colliding.get(1), "second");

        detector.recordRequestSent(colliding.get(1), new Object(), "unanswered");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
        assertEquals(java.util.List.of("second: 1 requests sent, only 0 responses received"),
                report.uncompletedRequests);
    }

    /** One response answers one send, not one on every client that has a send of that name. */
    @Test
    void oneResponseAnswersOneSendAcrossClients() {
        Object one = new Object();
        Object two = new Object();
        detector.recordClientCreated(one, "client-1");
        detector.recordClientCreated(two, "client-2");
        detector.recordRequestSent(one, new Object(), "call");
        detector.recordRequestSent(two, new Object(), "call");

        detector.recordResponseReceived(new Object(), "call");

        HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
        assertEquals(1, report.uncompletedRequests.size(),
                "two sends and one response leave one send unanswered: " + report.uncompletedRequests);
    }
}
