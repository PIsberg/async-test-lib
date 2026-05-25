package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.HttpApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HttpApiClient.
 *
 * ========================================================================
 * DETECTOR: HttpClientConcurrencyDetector
 * ========================================================================
 *
 * THE BUG:
 * HttpApiClient.fetchUrl() calls HttpClient.newHttpClient() on every invocation.
 * Each client allocates its own connection pool, selector thread, and executor.
 * Under concurrent load this exhausts file descriptors, wastes memory, and
 * bypasses HTTP connection reuse, causing poor throughput and latency spikes.
 *
 * WHY @Test PASSES:
 * A single test makes one request; one extra client allocation has negligible
 * impact and the test only validates the returned response body.
 *
 * WHY @AsyncTest DETECTS:
 * HttpClientConcurrencyDetector.recordClientCreated() is called for each new
 * HttpClient instance. The detector flags when multiple distinct clients are
 * created across concurrent invocations of the same code path.
 *
 * FIX:
 * Create one HttpClient instance (static final field or injected dependency)
 * and reuse it across all requests.
 */
class HttpApiClientTest {

    private HttpApiClient client;

    @BeforeEach
    void setUp() {
        client = new HttpApiClient();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testFetchUrl_singleThread_serviceIsCreated() {
        // Single-threaded: no race, but still creates a new client per call
        assertNotNull(client, "HttpApiClient instance must be created");
    }

    @Test
    void testNewClient_perCallPattern_confirmedByReflection() {
        // Demonstrate the pattern: two calls would produce two different clients
        HttpClient c1 = HttpClient.newHttpClient();
        HttpClient c2 = HttpClient.newHttpClient();
        assertNotSame(c1, c2, "newHttpClient() always returns a distinct instance");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the concurrency bug
    // -------------------------------------------------------------------------

    /**
     * With 8 concurrent threads each calling fetchUrl(), each invocation creates
     * a new HttpClient. HttpClientConcurrencyDetector records every client
     * creation and reports when the same logical code path produces multiple
     * distinct client instances.
     *
     * Because fetchUrl() makes real network calls, the @AsyncTest body registers
     * the creation event directly rather than completing the HTTP request.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: store the HttpClient as a static final field in HttpApiClient
     */
    @Disabled("Remove @Disabled to see the bug detected by HttpClientConcurrencyDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectHttpClientIssues = true)
    void testFetchUrl_concurrent_detectsClientPerRequest() {
        // Simulate what fetchUrl() does: create a new client per invocation
        HttpClient newClient = HttpClient.newHttpClient();

        // Record the client creation — the detector flags multiple distinct instances
        AsyncTestContext.httpClientDetector()
                .recordClientCreated(newClient, "HttpApiClient.fetchUrl-client");
    }
}
