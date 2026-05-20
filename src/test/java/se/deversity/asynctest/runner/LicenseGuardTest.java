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
