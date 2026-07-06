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
