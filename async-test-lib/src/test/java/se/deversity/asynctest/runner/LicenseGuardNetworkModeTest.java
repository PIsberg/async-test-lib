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
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what the licence gate does when the validator cannot be consulted, which is the case
 * enterprise CI actually lives in: egress-blocked runners, proxies, and provider outages.
 *
 * <p>The requirement has two halves that pull against each other. A validator that is genuinely
 * unreachable must not fail a licensed build (an outage at the licensing provider is not a fact
 * about the customer's licence). But common-license-lib maps <em>both</em> "host unreachable"
 * and "provider answered 401/429/5xx" to {@code NETWORK_ERROR}, so a blanket fail-open on that
 * reason would re-open the silent-grant hole pinned by {@link LicenseGuardTest}: fabricated
 * credentials would pass anywhere the validator answers with an error status. The grace path is
 * therefore allowed only when the failure is a connection-level one (probed directly) or when
 * this exact configuration has validated successfully before (recorded on disk).
 *
 * <p>Uses a loopback stand-in for the LemonSqueezy API, like {@link LicenseGuardLemonSqueezyTest},
 * so nothing here touches the real providers.
 */
class LicenseGuardNetworkModeTest {

    private static final long OUR_STORE = 42L;
    private static final String BUYER = "buyer@acme-corp.com";

    private HttpServer server;
    private int port;
    private final Map<String, String> savedProps = new HashMap<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "";

    @TempDir
    Path cacheDir;

    @BeforeEach
    void start() throws IOException {
        resetCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();

        // Surefire sets license.mock.mode=true for the whole build; leaving it on would make
        // every assertion below pass without the gate ever deciding anything.
        set("license.mock.mode", "false");
        set("license.provider", "lemonsqueezy");
        set("ls.api.base.uri", "http://127.0.0.1:" + port);
        set("ls.store.id", String.valueOf(OUR_STORE));
        set("license.user.email", BUYER);
        set("license.key", "TEST-KEY");
        set("ls.product.id", null);
        set("ls.email.binding", null);
        set("license.network.mode", null);              // library default under test
        set("license.file", null);
        set("license.cache.dir", cacheDir.toString());  // hermetic: no developer-machine state
        set("license.cache.ttl.hours", null);           // library default under test
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

    private void set(String key, String value) {
        // Not Map.putIfAbsent: it treats a null-valued mapping as absent, so a second set() of
        // a key that started unset would overwrite the saved baseline with the mid-test value,
        // and @AfterEach would "restore" the leak into every later test class in this JVM.
        if (!savedProps.containsKey(key)) {
            savedProps.put(key, System.getProperty(key));
        }
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            ex.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static String validPayload() {
        return "{\"valid\":true,\"error\":null,"
            + "\"license_key\":{\"status\":\"inactive\"},"
            + "\"meta\":{\"store_id\":" + OUR_STORE + ",\"customer_email\":\"" + BUYER + "\"}}";
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
    }

    /** Kills the stub so the configured base URI refuses connections outright. */
    private void makeValidatorUnreachable() {
        server.stop(0);
    }

    @Test
    void unreachableValidator_defaultGraceMode_doesNotFailTheBuild() {
        makeValidatorUnreachable();

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "A licensed build must survive a validator outage: connection-level failure "
            + "with grace mode (the default) proceeds with a warning instead of throwing");
    }

    @Test
    void unreachableValidator_strictMode_failsClosed() {
        makeValidatorUnreachable();
        set("license.network.mode", "strict");

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()),
            "strict mode restores the pre-grace behaviour for customers who want it");
        assertTrue(ex.getMessage().contains("NETWORK_ERROR"), ex.getMessage());
    }

    @Test
    void unknownNetworkMode_isRejectedRatherThanSilentlyDefaulting() {
        set("license.network.mode", "lenient");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LicenseGuard.check(config()),
            "A typo that silently fell back to grace would look like a policy, not a config error");
        assertTrue(ex.getMessage().contains("license.network.mode"), ex.getMessage());
    }

    @Test
    void reachableValidatorRejectingTheKey_failsClosedEvenUnderGrace() {
        responseStatus = 404;
        responseBody = "{\"valid\":false,\"error\":\"license_key not found\"}";

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()),
            "Grace is for outages, not for rejections: a validator that answered and said no "
            + "must still fail the build");
        assertTrue(ex.getMessage().contains("LICENSE_NOT_FOUND"), ex.getMessage());
    }

    @Test
    void reachableButErroringValidator_failsClosedWhenNothingEverValidated() {
        responseStatus = 500;
        responseBody = "boom";

        // The precondition is the whole premise of this test, so it is checked rather than
        // assumed. If a validation record existed, the outage grace policy would apply, no
        // SecurityException would be thrown, and the failure would read as a policy bug in
        // LicenseGuard instead of a dirty cache directory.
        assertTrue(isEmpty(cacheDir),
            "precondition: no validation record may exist for 'nothing ever validated' to mean "
            + "anything. Found: " + listing(cacheDir));

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()),
            "A host that answers HTTP but errors is indistinguishable from rejected fabricated "
            + "credentials (both are NETWORK_ERROR); with no successful validation on record the "
            + "gate must stay closed");
        assertTrue(ex.getMessage().contains("NETWORK_ERROR"), ex.getMessage());
    }

    @Test
    void erroringValidator_withAPastSuccessfulValidation_gracesOnTheRecord() {
        // ttl 0 keeps the success record but never treats it as fresh, so the second check is
        // forced back onto the network and exercises the grace decision rather than the cache skip.
        set("license.cache.ttl.hours", "0");

        responseStatus = 200;
        responseBody = validPayload();
        assertDoesNotThrow(() -> LicenseGuard.check(config()), "the priming validation must succeed");

        resetCache();
        responseStatus = 500;
        responseBody = "boom";

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "This configuration has validated successfully before, so a provider that is now "
            + "erroring is an outage for this customer, not a rejection");
    }

    @Test
    void freshCachedValidation_skipsTheNetworkEntirely() {
        responseStatus = 200;
        responseBody = validPayload();
        assertDoesNotThrow(() -> LicenseGuard.check(config()), "the priming validation must succeed");

        resetCache();
        makeValidatorUnreachable();
        set("license.network.mode", "strict");   // any network attempt would now fail closed

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "Within the cache TTL a new JVM must not revalidate at all: forkEvery=1 suites "
            + "would otherwise make one licensing API call per test class");
    }

    /** {@return whether {@code dir} holds no validation records} */
    private static boolean isEmpty(Path dir) {
        return listing(dir).isEmpty();
    }

    /** {@return the names of the files in {@code dir}, for an assertion message} */
    private static List<String> listing(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            return List.of("<unreadable: " + e + ">");
        }
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
