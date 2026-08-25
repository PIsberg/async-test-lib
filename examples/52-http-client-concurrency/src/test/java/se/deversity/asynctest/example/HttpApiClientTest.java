package se.deversity.asynctest.example;

import com.sun.net.httpserver.HttpServer;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.HttpClientConcurrencyDetector;
import se.deversity.asynctest.example.service.HttpApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HttpApiClient.
 *
 * ========================================================================
 * DETECTOR: HttpClientConcurrencyDetector
 * ========================================================================
 *
 * THE BUG:
 * HttpApiClient builds a new HttpClient on every call, and notifyAsync() then
 * drops the future sendAsync() returns. The request goes out and nothing in the
 * process ever establishes whether it worked.
 *
 * WHAT THIS DETECTOR ACTUALLY MODELS:
 * Requests that were sent and never completed, a request/response count
 * mismatch, and a concurrent request count high enough to exhaust a connection
 * pool. It does NOT report "many distinct HttpClient instances", which is what
 * this example used to claim and to record. recordClientCreated files the client
 * so requests have something to attach to; on its own it produces no finding, so
 * enabling the demonstration reported nothing at all. See issue #346.
 *
 * WHY THERE IS A SERVER IN HERE:
 * The requests are real, over loopback, to a com.sun.net.httpserver.HttpServer
 * this test starts on port 0. Recording a request/response lifecycle that never
 * happened would be the same mistake in a different costume, and a real socket on
 * 127.0.0.1 needs no network access from CI.
 *
 * DETECTOR ENABLED HERE:
 * HttpClientConcurrencyDetector — a request sent and never completed. It is the
 * only one this demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * Hold one HttpClient for the process, and complete every request you start,
 * even if completing it only means logging the failure.
 */
class HttpApiClientTest {

    private HttpApiClient client;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        client = new HttpApiClient();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.getAddress().getPort() + "/ping";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — the request that is completed, and the one that is not
    // -------------------------------------------------------------------------

    @Test
    void testFetchUrl_completesTheRequest() throws Exception {
        assertEquals("ok", client.fetchUrl(baseUrl));
    }

    /**
     * The detector's negative direction: a request that was sent and answered leaves nothing to
     * report. A detector that fired here would fire on every HTTP call ever made.
     */
    @Test
    void testHttpClientConcurrencyDetector_completedRequest_isSilent() throws Exception {
        HttpClientConcurrencyDetector detector = new HttpClientConcurrencyDetector();
        wire(detector);

        client.fetchUrl(baseUrl);

        assertFalse(detector.analyze().hasIssues(),
                "a request that got its response is an HTTP call working");
    }

    /**
     * And the positive direction: fire-and-forget leaves a request that nothing ever completes.
     */
    @Test
    void testHttpClientConcurrencyDetector_abandonedRequest_reports() {
        HttpClientConcurrencyDetector detector = new HttpClientConcurrencyDetector();
        wire(detector);

        client.notifyAsync(baseUrl);

        assertTrue(detector.analyze().hasIssues(),
                "the future was discarded, so no response is ever recorded for that request");
    }

    private void wire(HttpClientConcurrencyDetector detector) {
        client.observeHttp(
                created -> detector.recordClientCreated(created, "HttpApiClient"),
                (request, name) -> detector.recordRequestSent(request, name),
                (response, name) -> detector.recordResponseReceived(response, name));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * The bug: every thread fires a notification and walks away, on a client it also throws
     * away.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      HttpApiClient: N requests sent, only 0 responses received
     * 3. Fix: hold one client, and complete every request you start
     */
    @Disabled("Remove @Disabled to see the bug detected by HttpClientConcurrencyDetector")
    @AsyncTest(threads = 8, invocations = 3, detectAll = false,
            detectHttpClientIssues = true, failOn = FailOn.LOW)
    void testNotifyAsync_concurrent_detectsAbandonedRequests() {
        HttpClientConcurrencyDetector detector = AsyncTestContext.httpClientDetector();
        client.observeHttp(
                created -> detector.recordClientCreated(created, "HttpApiClient"),
                (request, name) -> detector.recordRequestSent(request, name),
                (response, name) -> detector.recordResponseReceived(response, name));

        client.notifyAsync(baseUrl);
    }
}
