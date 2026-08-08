package se.deversity.asynctest.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the LicenseGuard process-wide cache that replaced the per-invocation
 * license check previously hard-coded inside {@code ConcurrencyRunner.execute}.
 */
class LicenseGuardTest {

    @BeforeEach
    void reset() {
        resetCache();
    }

    @Test
    void firstCheck_populatesCache() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .build();
        assertEquals(0, LicenseGuard.cacheSize());
        LicenseGuard.check(cfg);
        assertEquals(1, LicenseGuard.cacheSize(),
                "First check must add exactly one fingerprint to the cache");
    }

    @Test
    void repeatedCheckSameConfig_hitsCache() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .build();
        for (int i = 0; i < 100; i++) {
            LicenseGuard.check(cfg);
        }
        assertEquals(1, LicenseGuard.cacheSize(),
                "100 calls with the same fingerprint must not grow the cache");
    }

    @Test
    void differentFingerprint_addsSeparateEntry() {
        AsyncTestConfig a = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .keygenAccountId("acc-A")
                .build();
        AsyncTestConfig b = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .keygenAccountId("acc-B")
                .build();
        LicenseGuard.check(a);
        LicenseGuard.check(b);
        assertEquals(2, LicenseGuard.cacheSize(),
                "Distinct account IDs must produce distinct cache entries");
    }

    @Test
    void changedLicenseUserEmailSysProp_producesDistinctFingerprint() {
        String prevEmail = System.getProperty("license.user.email");
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .licenseMockMode(true)
                    .build();

            System.setProperty("license.user.email", "first@example.com");
            LicenseGuard.check(cfg);
            assertEquals(1, LicenseGuard.cacheSize());

            System.setProperty("license.user.email", "second@example.com");
            LicenseGuard.check(cfg);
            assertEquals(2, LicenseGuard.cacheSize(),
                    "Changing license.user.email must invalidate the cached grant for an otherwise identical config");
        } finally {
            if (prevEmail != null) {
                System.setProperty("license.user.email", prevEmail);
            } else {
                System.clearProperty("license.user.email");
            }
        }
    }

    @Test
    void changedLicenseKeySysProp_producesDistinctFingerprint() {
        String prevKey = System.getProperty("license.key");
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .licenseMockMode(true)
                    .build();

            System.setProperty("license.key", "key-one");
            LicenseGuard.check(cfg);
            assertEquals(1, LicenseGuard.cacheSize());

            System.setProperty("license.key", "key-two");
            LicenseGuard.check(cfg);
            assertEquals(2, LicenseGuard.cacheSize(),
                    "Changing license.key must invalidate the cached grant for an otherwise identical config");
        } finally {
            if (prevKey != null) {
                System.setProperty("license.key", prevKey);
            } else {
                System.clearProperty("license.key");
            }
        }
    }

    @Test
    void cacheIsThreadSafe() throws Exception {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .build();
        int threads = 16;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> LicenseGuard.check(cfg));
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();
        assertEquals(1, LicenseGuard.cacheSize(),
                "Concurrent first-time checks for the same config must collapse to one cache entry");
    }

    @Test
    void expiredOrInvalidLicense_throwsSecurityException() {
        String prevMockMode = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "false");
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .licenseMockMode(false)
                    .keygenApiKey("dummy-api-key") // prevent auto-mocking in CI
                    // Real-shaped coordinates, so the run reaches the provider instead of being
                    // rejected up front by the placeholder guard below.
                    .keygenAccountId("acc-not-a-placeholder")
                    .keygenProductId("prod-not-a-placeholder")
                    .licenseKey("expired-or-invalid-key")
                    .build();

            SecurityException ex = assertThrows(SecurityException.class, () -> {
                LicenseGuard.check(cfg);
            });

            assertTrue(ex.getMessage().contains("LICENSE DENIED"));
            assertTrue(ex.getMessage().contains("To run locally without a key"));
        } finally {
            if (prevMockMode != null) {
                System.setProperty("license.mock.mode", prevMockMode);
            } else {
                System.clearProperty("license.mock.mode");
            }
        }
    }

    /**
     * Regression test for a silent grant measured against the published 1.7.3 artifact: with
     * {@code -Dlicense.mock.mode=false} and an obviously fabricated key, {@code mvn test}
     * produced BUILD SUCCESS. The key was checked against the {@code dummy-account} placeholder,
     * so a valid key, a typo and a fabricated key were indistinguishable and all granted. The
     * gate must now refuse to answer a question it cannot ask.
     */
    @Test
    void licenseKeyWithPlaceholderAccountId_failsClosedInsteadOfGranting() {
        String prevMockMode = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "false");
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .licenseMockMode(false)
                    .licenseKey("INVALID-TEST-KEY-0000")   // no account/product id supplied
                    .build();

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> LicenseGuard.check(cfg),
                    "A key that cannot be validated against anything must not be granted");
            assertTrue(ex.getMessage().contains("LICENSE MISCONFIGURED"),
                    "The failure must say the run is misconfigured, not that the key is bad: "
                    + ex.getMessage());
            assertTrue(ex.getMessage().contains("-Dkeygen.account.id"),
                    "The message must name the missing property: " + ex.getMessage());
        } finally {
            restore("license.mock.mode", prevMockMode);
        }
    }

    /**
     * The no-key path, pinned as documented rather than as assumed. A local run with no key and
     * no mock flag is refused with {@code LICENSE_REQUIRED} — the library is commercial and the
     * README's "the gate runs and can refuse" table says exactly this. Measured, not inferred:
     * an earlier reading of this path as permissive came from an example that uses plain
     * {@code @Test} and therefore never reaches the gate at all.
     */
    @Test
    void noLicenseKeyLocally_isRefusedAsDocumented() {
        String prevMockMode = System.getProperty("license.mock.mode");
        String prevKey = System.getProperty("license.key");
        System.setProperty("license.mock.mode", "false");
        System.clearProperty("license.key");
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .licenseMockMode(false)
                    .keygenAccountId("acc-not-a-placeholder")
                    .keygenProductId("prod-not-a-placeholder")
                    .build();

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> LicenseGuard.check(cfg),
                    "An unlicensed run outside CI must be refused, as README documents");
            assertTrue(ex.getMessage().contains("LICENSE DENIED"),
                    "The refusal must be the licence denial, not a config error: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("-Dlicense.mock.mode=true"),
                    "The message must tell an evaluator how to proceed: " + ex.getMessage());
        } finally {
            restore("license.mock.mode", prevMockMode);
            restore("license.key", prevKey);
        }
    }

    /**
     * Regression test for the defect that made paid licences unenforceable in CI.
     *
     * <p>The auto-mock branch is {@code isCi && !hasCredentials}, and {@code hasCredentials} used
     * to mean "a Keygen API token is set". Customers are never issued an API token, so every
     * customer CI run took the auto-mock branch: the key was never sent anywhere and the run was
     * announced as GRANTED. Measured on this branch with {@code CI=true} and an invalid key, the
     * old condition returned GRANTED and the fixed condition returns
     * {@code LICENSE DENIED: LICENSE_NOT_FOUND}.
     *
     * <p>Asserted against the decision function rather than {@code check()}, because a test
     * cannot set an environment variable for its own JVM.
     */
    @Test
    void licenseKeyAloneMakesTheRunCredentialled_soCiCannotAutoMockIt() {
        assertTrue(LicenseGuard.assertsCommercialLicence(false, "a-customer-key", null, null),
                "A Keygen customer has a key and no API token; that run must still be validated");
        assertTrue(LicenseGuard.assertsCommercialLicence(false, null, "an-operator-token", null),
                "An operator token still counts, which is what the project's own tooling uses");
        assertFalse(LicenseGuard.assertsCommercialLicence(false, null, null, null),
                "A run asserting no licence at all is what CI auto-mock exists for");
        assertFalse(LicenseGuard.assertsCommercialLicence(false, "   ", null, null),
                "A blank key asserts nothing");
    }

    /** LemonSqueezy needs the store id as well as the key before a check can mean anything. */
    @Test
    void lemonSqueezyNeedsBothKeyAndStoreIdToCount() {
        assertTrue(LicenseGuard.assertsCommercialLicence(true, "a-key", null, 42L));
        assertFalse(LicenseGuard.assertsCommercialLicence(true, "a-key", null, null),
                "A key with no store id cannot be scoped, so it is not a commercial assertion");
        assertFalse(LicenseGuard.assertsCommercialLicence(true, null, null, 42L),
                "A store id with no key asserts nothing");
    }

    /**
     * Mock mode is the documented escape hatch and outranks the guard: a misconfigured run that
     * has explicitly opted out of validation is not asserting a licence.
     */
    @Test
    void mockMode_outranksThePlaceholderGuard() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .licenseMockMode(true)
                .licenseKey("INVALID-TEST-KEY-0000")
                .build();

        assertDoesNotThrow(() -> LicenseGuard.check(cfg),
                "-Dlicense.mock.mode=true must keep working regardless of provider config");
    }

    private static void restore(String key, String previous) {
        if (previous != null) {
            System.setProperty(key, previous);
        } else {
            System.clearProperty(key);
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
