package se.deversity.asynctest.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.ConcurrencyTestFor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dogfoods {@link LicenseGuard}'s at-most-once gate with {@code @AsyncTest}.
 *
 * <p>Why this exists: {@code LicenseGuard} claims that concurrent first checks of one configuration
 * run the gate once, and every caller after that takes the fast path. With {@code forkCount} JVMs
 * and parallel test classes, a gate that ran per caller would multiply the provider calls of every
 * licensed build, and the provider rate-limits. This replaces {@code
 * LicenseGuardTest.cacheIsThreadSafe}, which could not see that: its threads started without a
 * barrier, and it counted cache entries, which a {@code ConcurrentHashMap} keeps at one even when
 * the gate ran once per thread.
 *
 * <p>So this counts what a customer would pay for: requests to a loopback Keygen stand-in.
 * {@link #THREADS} workers collide on the barrier at the first check of one fingerprint, then keep
 * checking for {@link #ROUNDS} rounds, and the stand-in must have been asked exactly once. Strict
 * network mode and a disabled validation cache mean no grace path and no disk record can stand in
 * for the request.
 */
@ConcurrencyTestFor(LicenseGuard.class)
class LicenseGuardGateOnceDogfoodTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 25;

    private static final String VALID = "{\"meta\":{\"ts\":\"2026-08-06T00:00:00.000Z\",\"valid\":true,"
            + "\"detail\":\"is valid\",\"code\":\"VALID\"},"
            + "\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\","
            + "\"attributes\":{\"key\":\"TEST-KEY-GATE-ONCE\",\"status\":\"ACTIVE\"}}}";

    private static final AtomicInteger VALIDATIONS = new AtomicInteger();
    private static final Map<String, String> SAVED = new HashMap<>();
    private static HttpServer server;

    @TempDir
    static Path cacheDir;

    @BeforeAll
    static void startTheProvider() throws IOException {
        LicenseGuard.resetForTesting();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", LicenseGuardGateOnceDogfoodTest::validate);
        server.start();

        // Surefire sets license.mock.mode=true for the whole build; this test exists to exercise
        // the gate. The enclosing @AsyncTest stays mocked through its own attribute.
        set("license.mock.mode", "false");
        set("license.provider", null);
        set("keygen.base.uri", "http://127.0.0.1:" + server.getAddress().getPort());
        set("keygen.account.id", "acct-gate-once");
        set("keygen.product.id", "prod-gate-once");
        set("keygen.api.key", null);
        set("license.key", "TEST-KEY-GATE-ONCE");
        set("license.user.email", "buyer@acme-corp.com");
        set("license.network.mode", "strict");
        set("license.cache.ttl.hours", "-1");
        set("license.cache.dir", cacheDir.toString());
        set("license.file", null);
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, licenseMockMode = true, timeoutMs = 20_000, detectAll = true)
    void everyWorkerChecksTheSameConfiguration() {
        LicenseGuard.check(AsyncTestConfig.builder().licenseMockMode(false).build());
    }

    @AfterAll
    static void theProviderWasAskedOnce() {
        try {
            assertEquals(1, VALIDATIONS.get(),
                    "the gate ran more than once for one configuration: concurrent first checks "
                            + "each called the provider, so every licensed build multiplies its "
                            + "licensing API calls by its thread count");
        } finally {
            server.stop(0);
            SAVED.forEach((k, v) -> {
                if (v == null) System.clearProperty(k);
                else System.setProperty(k, v);
            });
            LicenseGuard.resetForTesting();
        }
    }

    private static void validate(HttpExchange ex) throws IOException {
        try (ex) {
            VALIDATIONS.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body = VALID.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void set(String key, String value) {
        if (!SAVED.containsKey(key)) {
            SAVED.put(key, System.getProperty(key));
        }
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
