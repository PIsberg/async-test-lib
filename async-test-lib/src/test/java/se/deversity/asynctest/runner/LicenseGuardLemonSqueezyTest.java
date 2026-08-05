package se.deversity.asynctest.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@code -Dlicense.provider=lemonsqueezy} end-to-end through {@link LicenseGuard}, against a
 * loopback stand-in for LemonSqueezy's License API so the suite stays hermetic.
 *
 * <p>Every test here uses a commercial email on purpose. A free-provider address short-circuits the
 * gate to {@code FREE_PROVIDER_EMAIL} before any key is examined, so a "passing" license test
 * written with a gmail.com address would prove nothing at all.
 */
class LicenseGuardLemonSqueezyTest {

    private static final long OUR_STORE = 42L;
    private static final String BUYER = "buyer@acme-corp.com";

    private HttpServer server;
    private final Map<String, String> savedProps = new HashMap<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        resetCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        // Surefire sets license.mock.mode=true for the whole build; leaving it on would make every
        // assertion below pass without a single byte crossing the wire.
        set("license.mock.mode", "false");
        set("license.provider", "lemonsqueezy");
        set("ls.api.base.uri", "http://127.0.0.1:" + server.getAddress().getPort());
        set("ls.store.id", String.valueOf(OUR_STORE));
        set("license.user.email", BUYER);
        set("license.key", "TEST-KEY");
        set("ls.product.id", null);
        set("ls.email.binding", null);   // library default is DOMAIN
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

    /** Sets a system property, remembering the previous value for restoration. */
    private void set(String key, String value) {
        savedProps.putIfAbsent(key, System.getProperty(key));
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            lastPath.set(ex.getRequestURI().getPath());
            ex.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static String validPayload(long storeId, String email) {
        return "{\"valid\":true,\"error\":null,"
            + "\"license_key\":{\"status\":\"inactive\"},"
            + "\"meta\":{\"store_id\":" + storeId + ",\"customer_email\":\"" + email + "\"}}";
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
    }

    @Test
    void validLemonSqueezyKeyIsAccepted() {
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, BUYER);

        assertDoesNotThrow(() -> LicenseGuard.check(config()));
        assertEquals("/v1/licenses/validate", lastPath.get(),
            "the run must reach LemonSqueezy, not Keygen");
    }

    @Test
    void keyFromAnotherStoreIsRejected() {
        responseStatus = 200;
        responseBody = validPayload(999L, BUYER);

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("LICENSE DENIED"), ex.getMessage());
    }

    @Test
    void colleagueOnTheBuyersDomainIsCoveredByTheCompanyLicence() {
        // A company buys once as billing@acme-corp.com; every developer on that domain must be
        // able to run, or a company licence is unusable by the company that bought it.
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, "billing@acme-corp.com");
        set("license.user.email", "alice@acme-corp.com");

        assertDoesNotThrow(() -> LicenseGuard.check(config()));
    }

    @Test
    void keyFromAnotherOrganisationIsRejected() {
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, "billing@other-corp.com");

        assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
    }

    @Test
    void exactBindingNarrowsToTheBuyingAddress() {
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, "billing@acme-corp.com");
        set("ls.email.binding", "exact");
        set("license.user.email", "alice@acme-corp.com");

        assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
    }

    @Test
    void unknownEmailBindingIsRejected() {
        set("ls.email.binding", "sorta");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("ls.email.binding"), ex.getMessage());
    }

    @Test
    void unknownKeyIsRejected() {
        responseStatus = 404;
        responseBody = "{\"valid\":false,\"error\":\"license_key not found\"}";

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("LICENSE_NOT_FOUND"), ex.getMessage());
    }

    @Test
    void deniedGuidanceMentionsTheLemonSqueezyProperties() {
        responseStatus = 404;
        responseBody = "{\"valid\":false,\"error\":\"license_key not found\"}";

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("-Dlicense.provider=lemonsqueezy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("-Dls.store.id="), ex.getMessage());
    }

    @Test
    void changingStoreIdInvalidatesTheCachedGrant() {
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, BUYER);
        LicenseGuard.check(config());
        assertEquals(1, LicenseGuard.cacheSize());

        // A different store must not inherit the previous store's GRANTED decision.
        set("ls.store.id", "999");
        responseBody = validPayload(999L, BUYER);
        LicenseGuard.check(config());
        assertEquals(2, LicenseGuard.cacheSize(),
            "store id is part of the fingerprint, so the decision must be recomputed");
    }

    @Test
    void changingProviderInvalidatesTheCachedGrant() {
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, BUYER);
        LicenseGuard.check(config());
        assertEquals(1, LicenseGuard.cacheSize());

        set("license.provider", "keygen");
        set("license.mock.mode", "true");   // do not reach out to Keygen from a unit test
        LicenseGuard.check(config());
        assertEquals(2, LicenseGuard.cacheSize(),
            "provider is part of the fingerprint, so switching it must recompute the decision");
    }

    @Test
    void unknownProviderIsRejectedRatherThanSilentlyFallingBack() {
        set("license.provider", "keeygen");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("Unknown license.provider"), ex.getMessage());
    }

    @Test
    void providerKeywordIsCaseInsensitiveButAsciiOnly() {
        // Mixed case is accepted.
        set("license.provider", "LemonSqueezy");
        responseStatus = 200;
        responseBody = validPayload(OUR_STORE, BUYER);
        assertDoesNotThrow(() -> LicenseGuard.check(config()));

        // But a character outside ASCII that Unicode case-folding would map onto an ASCII
        // letter must not be able to impersonate the keyword. U+212A is the Kelvin sign,
        // which String.toLowerCase folds to a plain 'k' — asciiLower leaves it alone, so
        // this falls through to the default branch instead of selecting a provider.
        resetCache();
        set("license.provider", "Keygen");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("Unknown license.provider"), ex.getMessage());
    }

    @Test
    void nonNumericStoreIdIsRejected() {
        set("ls.store.id", "not-a-number");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("ls.store.id"), ex.getMessage());
    }

    @Test
    void freeProviderEmailNeverReachesLemonSqueezy() {
        responseStatus = 500;                 // would deny if it were consulted
        responseBody = "boom";
        set("license.user.email", "someone@gmail.com");

        assertDoesNotThrow(() -> LicenseGuard.check(config()));
        assertNull(lastPath.get(), "a free-provider address must short-circuit before the API call");
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
