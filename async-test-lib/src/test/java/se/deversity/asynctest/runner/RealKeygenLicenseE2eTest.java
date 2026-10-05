package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.E2E;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof against the <em>live</em> Keygen account with the real Deversity AB licence:
 * a genuine key validates, and the exact-owner binding really denies a colleague. This is the
 * in-suite twin of the operator runbook's {@code verify-license.sh}, and the only test that
 * exercises a real grant over the network - every other licence test is hermetic by design.
 *
 * <p><b>Where it runs.</b> In the {@code license-e2e.yml} workflow, from repository secrets, and
 * on the operator machine once {@code ~/.config/deversity/e2e-license.env} is sourced. With none
 * of the {@code ATL_E2E_*} variables set it skips, so contributor machines and the main CI legs
 * are unaffected; a partial set, or no set where {@code ATL_E2E_REQUIRED=true}, fails instead
 * ({@link LicenseE2eEnvironment}). Run it with:
 *
 * <pre>{@code
 * set -a; . ~/.config/deversity/e2e-license.env; set +a
 * mvn -pl async-test-lib test -Dtest=RealKeygenLicenseE2eTest -DfailIfNoTests=false -P e2e
 * }</pre>
 *
 * <p><b>Why strict mode and a disabled cache are not optional.</b> Three mechanisms can grant
 * without the provider being consulted: CI auto-mock (defeated by supplying real credentials),
 * outage grace (defeated by {@code license.network.mode=strict}), and the cross-JVM validation
 * cache (defeated by {@code license.cache.ttl.hours=-1}). With all three closed, a green run
 * can only mean the live provider said yes - and the decoy test can only go red for the right
 * reason, which is why it also asserts the denial is not a {@code NETWORK_ERROR}.
 *
 * <p>The licence expires yearly (see the renewal note in the env file); when it lapses this
 * test fails with {@code LICENSE_EXPIRED}, which is itself the correct end-to-end signal.
 */
@E2E
class RealKeygenLicenseE2eTest {

    private final Map<String, String> savedProps = new HashMap<>();

    private String accountId;
    private String productId;
    private String licenseKey;
    private String licensedEmail;

    @BeforeEach
    void setUp() {
        Map<String, String> env = LicenseE2eEnvironment.require(System.getenv(), List.of(
            "ATL_E2E_KEYGEN_ACCOUNT_ID", "ATL_E2E_KEYGEN_PRODUCT_ID",
            "ATL_E2E_LICENSE_KEY", "ATL_E2E_LICENSE_EMAIL"));
        accountId = env.get("ATL_E2E_KEYGEN_ACCOUNT_ID");
        productId = env.get("ATL_E2E_KEYGEN_PRODUCT_ID");
        licenseKey = env.get("ATL_E2E_LICENSE_KEY");
        licensedEmail = env.get("ATL_E2E_LICENSE_EMAIL");

        resetCache();
        // Surefire sets license.mock.mode=true for the whole build; this test exists to
        // exercise the gate for real.
        set("license.mock.mode", "false");
        set("license.provider", null);                 // Keygen is the default
        set("keygen.account.id", accountId);
        set("keygen.product.id", productId);
        set("keygen.api.key", null);                   // customers never send a bearer
        set("keygen.base.uri", null);                  // the real host, not a stub
        set("license.key", licenseKey);
        set("license.user.email", licensedEmail);
        set("license.network.mode", "strict");         // grace must not be able to fake a grant
        set("license.cache.ttl.hours", "-1");          // nor a cached prior validation
        set("license.file", null);
    }

    @AfterEach
    void tearDown() {
        savedProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        savedProps.clear();
        resetCache();
    }

    @Test
    void theRealKeyValidatesAgainstTheLiveProvider() {
        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "The Deversity AB key must validate online with mock, grace and cache all closed; "
            + "a failure here is a real licensing outage, an expired licence, or a revoked key");
    }

    @Test
    void aColleagueOnTheSameDomainIsDeniedByTheExactOwnerBinding() {
        set("license.user.email", "e2e-decoy@deversity.se");

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()),
            "Keygen binds to the exact owner address; a same-domain colleague passing would "
            + "mean the scope is not enforced and a green run proves nothing");
        assertTrue(ex.getMessage().contains("LICENSE DENIED"), ex.getMessage());
        assertFalse(ex.getMessage().contains("NETWORK_ERROR"),
            "The decoy must be denied by the provider's answer, not by failing to reach it: "
            + ex.getMessage());
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
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
