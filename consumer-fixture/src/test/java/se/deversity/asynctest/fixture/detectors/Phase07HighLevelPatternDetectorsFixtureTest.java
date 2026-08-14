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
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.registerOnce;
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
        // One HttpClient shared by every worker: it owns a connection pool and a selector
        // thread, so the sharing is what the detector reasons about.
        var httpDetector = AsyncTestContext.httpClientDetector();
        httpDetector.recordClientCreated(SHARED_CLIENT, "shared-http-client");
        httpDetector.recordRequestSent(SHARED_CLIENT, "shared-http-client");
        SHARED_CLIENT.connectTimeout().ifPresent(timeout -> spin((int) timeout.toMillis()));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STREAM_CLOSING})
    void streamClosing() {
        reachable("streamClosingDetector()", AsyncTestContext::streamClosingDetector);

        // A resource-backed stream must be closed; try-with-resources is the fix the
        // detector's finding asks for.
        // A stream over a resource that is opened and never closed is the leak. The close
        // below is deliberately not recorded: the missing recordStreamClosed is the finding.
        var streamDetector = AsyncTestContext.streamClosingDetector();
        try (Stream<Integer> stream = List.of(1, 2, 3).stream()) {
            streamDetector.recordStreamOpened(() -> { }, "unclosed-stream");
            stream.mapToInt(Integer::intValue).sum();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CACHE_CONCURRENCY})
    void cacheConcurrency() {
        reachable("cacheConcurrencyDetector()", AsyncTestContext::cacheConcurrencyDetector);

        // computeIfAbsent on a shared cache: correct here, a cache stampede when the
        // mapping function is expensive and the map is not concurrent.
        // A plain HashMap used as a shared cache is the hazard: the detector deliberately does
        // not flag a ConcurrentMap, so the earlier ConcurrentHashMap version of this fixture
        // could not have failed. Reads and writes both have to happen for the finding.
        var cacheDetector = AsyncTestContext.cacheConcurrencyDetector();
        registerOnce("cache", () -> cacheDetector.registerCache(UNSAFE_CACHE, "shared-cache"));
        synchronized (UNSAFE_CACHE) {
            cacheDetector.recordGet(UNSAFE_CACHE, "shared-cache", "key");
            Integer existing = UNSAFE_CACHE.get("key");
            if (existing == null) {
                UNSAFE_CACHE.put("key", spin(64));
                cacheDetector.recordPut(UNSAFE_CACHE, "shared-cache", "key",
                        UNSAFE_CACHE.get("key"));
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COMPLETABLEFUTURE_CHAIN})
    void completableFutureChain() {
        reachable("cfChainDetector()", AsyncTestContext::cfChainDetector);

        // A long chain is harder to reason about and each stage can hop threads; the detector
        // counts the stages built on one root future.
        var chainDetector = AsyncTestContext.cfChainDetector();
        CompletableFuture<Integer> root = CompletableFuture.supplyAsync(() -> 1);
        chainDetector.recordFutureCreated(root, "chain-root");
        CompletableFuture<Integer> stage = root;
        for (int i = 0; i < 6; i++) {
            CompletableFuture<Integer> next = stage.thenApply(v -> v + 1);
            chainDetector.recordChainOperation(stage, next, "thenApply");
            stage = next;
        }
        // No recordExceptionally and no recordFutureJoined: a chain with neither an exception
        // handler nor anything consuming its result is the finding.
        stage.join();

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

    /** Unsynchronised on purpose: a HashMap used as a cache is what the detector watches for. */
    private static final java.util.Map<String, Integer> UNSAFE_CACHE = new java.util.HashMap<>();
}
