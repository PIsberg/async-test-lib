package se.deversity.asynctest.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTestConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorded contract between this library and Keygen's
 * <a href="https://keygen.sh/docs/api/licenses/#licenses-actions-validate-key">validate-key</a>
 * endpoint, replayed against a loopback stand-in so no build touches the real provider.
 *
 * <p><strong>Why this exists.</strong> {@code RealKeygenLicenseE2eTest} proves the integration
 * against the live API but needs an operator's key and is skipped everywhere else, and
 * {@code LicenseGuardNetworkModeTest} covers outages, not the happy path or a rejection with a
 * reason code. Nothing pinned the shape this library sends or the shape it decides on, so a
 * change on either side (a renamed scope key, a decision made on the wrong field) could only
 * surface on the operator's machine. The request and response shapes here were verified against
 * the live API on 2026-08-06 (see {@code KeygenValidator} in common-license-lib): the request is a
 * {@code POST} to {@code /v1/accounts/{account}/licenses/actions/validate-key} carrying
 * {@code meta.key} and {@code meta.scope.user}; the response is {@code 200} even for a bad key,
 * and the decision lives in {@code meta.valid} and {@code meta.code}.
 *
 * <p>Both directions are asserted: a valid answer admits the run, and each rejection code the
 * gate maps fails it closed with that code in the message. A contract test that only checked the
 * happy path would pass through a validator that admits everything.
 */
class KeygenValidateKeyContractTest {

    private static final String ACCOUNT = "acct-0000-test";
    private static final String PRODUCT = "prod-async-test";
    private static final String USER = "buyer@acme-corp.com";

    private HttpServer server;
    private final Map<String, String> savedProps = new HashMap<>();
    private volatile String responseBody = "";
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @TempDir
    Path cacheDir;

    @BeforeEach
    void start() throws IOException {
        resetCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        int port = server.getAddress().getPort();

        // Surefire sets license.mock.mode=true for the whole build; leaving it on would make
        // every assertion below pass without the gate ever deciding anything.
        set("license.mock.mode", "false");
        set("license.provider", null);                  // Keygen is the default provider
        set("keygen.base.uri", "http://127.0.0.1:" + port);
        set("keygen.account.id", ACCOUNT);
        set("keygen.product.id", PRODUCT);
        set("keygen.api.key", null);                    // customers never send a bearer
        set("license.key", "TEST-KEY-0001");
        set("license.user.email", USER);
        set("license.network.mode", "strict");          // grace must not be able to fake a grant
        set("license.cache.ttl.hours", "-1");           // nor a cached prior validation
        set("license.cache.dir", cacheDir.toString()); // hermetic: no developer-machine state
        set("license.file", null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        savedProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        resetCache();
    }

    // ---- the request half of the contract ----

    @Test
    void sendsTheDocumentedRequest_pathMethodAndScopedBody() {
        responseBody = valid();
        assertDoesNotThrow(() -> LicenseGuard.check(config()));

        assertEquals("POST", seenMethod.get(), "validate-key is a POST");
        assertEquals("/v1/accounts/" + ACCOUNT + "/licenses/actions/validate-key", seenPath.get(),
                "the account id is part of the path; a wrong path validates against nothing");
        String body = seenBody.get();
        assertTrue(body.contains("\"key\":\"TEST-KEY-0001\""),
                "the licence key travels as meta.key; body was: " + body);
        assertTrue(body.contains("\"user\":\"" + USER + "\""),
                "the run is bound to the licensed user via meta.scope.user (the formerly used "
                        + "scope.email is rejected by the live API); body was: " + body);
        assertTrue(body.contains("\"product\":\"" + PRODUCT + "\""),
                "with a product id configured the validation is scoped to it via "
                        + "meta.scope.product; body was: " + body);
    }

    // ---- the response half: both directions ----

    @Test
    void metaValidTrue_admitsTheRun() {
        responseBody = valid();
        assertDoesNotThrow(() -> LicenseGuard.check(config()),
                "meta.valid=true is the only answer that admits a run");
    }

    /**
     * A key Keygen validated is a Paddle customer's key, and that run must not be told to buy a
     * licence. This is the path deversity.se sells (Paddle + Keygen), so the silence is pinned here
     * and not only on the LemonSqueezy harness. Strict network mode and a negative cache TTL above
     * mean the grant below can only be {@code LICENSE_VALID} from the loopback validator.
     */
    @Test
    void metaValidTrue_doesNotPrintTheNoncommercialNotice() {
        responseBody = valid();

        String captured = LicenseGuardTest.captureStdErr(() -> LicenseGuard.check(config()));

        assertEquals("/v1/accounts/" + ACCOUNT + "/licenses/actions/validate-key", seenPath.get(),
                "precondition: the key was validated against Keygen");
        assertFalse(captured.contains("PolyForm Noncommercial"),
                "A Keygen-validated run is a paying customer's run and must be silent about "
                        + "non-commercial terms. stderr was:\n" + captured);
    }

    @Test
    void metaValidFalse_failsClosedNamingTheCode_forEachMappedCode() {
        for (String code : new String[] {"EXPIRED", "SUSPENDED", "NOT_FOUND",
                "USER_SCOPE_MISMATCH", "PRODUCT_SCOPE_MISMATCH", "FINGERPRINT_SCOPE_MISMATCH"}) {
            resetCache();
            responseBody = "{\"meta\":{\"valid\":false,\"code\":\"" + code + "\","
                    + "\"detail\":\"stub\"}}";
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> LicenseGuard.check(config()),
                    "Keygen answered 200 with meta.valid=false (" + code
                            + "); the gate must fail closed, not treat 200 as a grant");
            assertTrue(ex.getMessage().contains("LICENSE") || ex.getMessage().contains(code),
                    "the refusal must be recognisable as a licence decision; was: "
                            + ex.getMessage());
        }
    }

    @Test
    void aBodyWithoutMetaValid_isNotAGrant() {
        responseBody = "{\"data\":{\"type\":\"licenses\"}}";
        assertThrows(SecurityException.class, () -> LicenseGuard.check(config()),
                "a 200 whose body does not say meta.valid=true must not admit the run: the "
                        + "decision is on the field, never on the status");
    }

    // ---- helpers ----

    private static String valid() {
        return "{\"meta\":{\"ts\":\"2026-08-06T00:00:00.000Z\",\"valid\":true,"
                + "\"detail\":\"is valid\",\"code\":\"VALID\"},"
                + "\"data\":{\"id\":\"lic-1\",\"type\":\"licenses\","
                + "\"attributes\":{\"key\":\"TEST-KEY-0001\",\"status\":\"ACTIVE\"}}}";
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            seenMethod.set(ex.getRequestMethod());
            seenPath.set(ex.getRequestURI().getPath());
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void set(String key, String value) {
        savedProps.putIfAbsent(key, System.getProperty(key));
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void resetCache() {
        try {
            Method m = LicenseGuard.class.getDeclaredMethod("resetForTesting");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
