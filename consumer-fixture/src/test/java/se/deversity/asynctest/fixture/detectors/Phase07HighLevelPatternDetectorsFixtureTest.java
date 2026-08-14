package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 7, high-level-pattern group — {@code HTTP_CLIENT} through
 * {@code COMPLETABLEFUTURE_CHAIN}.
 *
 * <p>The HTTP fixture builds a client but never sends a request: the consumer fixture runs
 * behind a blocked-egress CI runner, and the detector's subject is client sharing and
 * configuration, not traffic.
 *
 * <p>Corresponding examples: {@code examples/52-http-client-concurrency},
 * {@code examples/77-stream-closing}, {@code examples/36-cache-concurrency},
 * {@code examples/37-cf-chain}.
 */
class Phase07HighLevelPatternDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "HttpClientConcurrencyDetector",
                    "StreamClosingDetector",
                    "CacheConcurrencyDetector",
                    "CompletableFutureChainDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.HTTP_CLIENT})
    void httpClient() {
        reachable("httpClientDetector()", AsyncTestContext::httpClientDetector);

        // One client shared across workers is the recommended pattern and the one the
        // detector reasons about; per-call clients are the leak it flags.
        SHARED_CLIENT.connectTimeout().ifPresent(timeout -> spin((int) timeout.toMillis()));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STREAM_CLOSING})
    void streamClosing() {
        reachable("streamClosingDetector()", AsyncTestContext::streamClosingDetector);

        // A resource-backed stream must be closed; try-with-resources is the fix the
        // detector's finding asks for.
        try (Stream<Integer> stream = List.of(1, 2, 3).stream()) {
            stream.mapToInt(Integer::intValue).sum();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CACHE_CONCURRENCY})
    void cacheConcurrency() {
        reachable("cacheConcurrencyDetector()", AsyncTestContext::cacheConcurrencyDetector);

        // computeIfAbsent on a shared cache: correct here, a cache stampede when the
        // mapping function is expensive and the map is not concurrent.
        CACHE.computeIfAbsent("key", k -> spin(64));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLEFUTURE_CHAIN})
    void completableFutureChain() {
        reachable("cfChainDetector()", AsyncTestContext::cfChainDetector);

        CompletableFuture.supplyAsync(() -> 1)
            .thenApply(v -> v + 1)
            .thenApply(v -> v * 2)
            .thenApply(String::valueOf)
            .exceptionally(t -> "recovered")
            .join();
    }

    /** Deliberately one client for the whole fixture — the shared-client pattern. */
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private static final ConcurrentMap<String, Integer> CACHE = new ConcurrentHashMap<>();
}
