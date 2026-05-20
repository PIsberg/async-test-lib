package se.deversity.asynctest.runner;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.common.license.LicenseConfig;
import se.deversity.common.license.LicenseGate;
import se.deversity.common.license.LicenseResult;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide license check for {@code @AsyncTest} execution.
 *
 * <p>Previously the gate ran inside {@code ConcurrencyRunner.execute} on every
 * single test invocation — a fresh {@code LicenseGate} construction plus a
 * {@code gate.check(...)} call per test. This class caches the outcome keyed by
 * a fingerprint of the relevant license-config inputs, so the actual gate work
 * runs at most once per (config-shape × JVM).
 *
 * <p>The fingerprint includes both the {@code @AsyncTest} config fields and
 * the resolved system-property fallbacks; if those change at runtime the
 * fingerprint changes and a fresh check fires.
 *
 * <p>Denied results throw {@link SecurityException}, mirroring the original
 * runner behavior. Granted results are announced once per JVM (not per test).
 */
public final class LicenseGuard {

    private static final ConcurrentMap<Fingerprint, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean announcedCiMock = false;
    private static volatile boolean announcedGranted = false;

    private LicenseGuard() {}

    /**
     * Validates the license for the given config. Throws {@link SecurityException}
     * if denied. Subsequent calls with the same fingerprint return immediately.
     */
    public static void check(AsyncTestConfig config) {
        Fingerprint fp = Fingerprint.from(config);
        if (CACHE.containsKey(fp)) return; // fast path
        CACHE.computeIfAbsent(fp, k -> {
            performCheck(k);
            return Boolean.TRUE;
        });
    }

    private static void performCheck(Fingerprint fp) {
        boolean isCi   = System.getenv("GITHUB_ACTIONS") != null || System.getenv("CI") != null;
        boolean hasKey = fp.keygenApiKey != null && !fp.keygenApiKey.isBlank();
        boolean mock   = fp.licenseMockMode
                       || Boolean.getBoolean("license.mock.mode")
                       || (isCi && !hasKey);
        String keygenKeyForCheck = (fp.keygenApiKey == null) ? "dummy-key" : fp.keygenApiKey;

        LicenseGate gate = LicenseGate.of(
            LicenseConfig.builder()
                .keygenAccountId(fp.keygenAccountId)
                .keygenApiKey(keygenKeyForCheck)
                .keygenProductId(fp.keygenProductId)
                .lemonSqueezyStoreSubdomain(fp.lemonSqueezyStore)
                .mockMode(mock)
                .build()
        );

        if (mock && isCi && !hasKey && !announcedCiMock) {
            announcedCiMock = true;
            System.out.println("LICENSE: Zero-Config CI mode active (Auto-Mocked)");
        }

        LicenseResult result = gate.check("user@example.com", fp.licenseKey);

        if (result instanceof LicenseResult.Denied denied) {
            String msg = "LICENSE DENIED: " + denied.reason()
                + (denied.message() != null ? " - " + denied.message() : "");
            System.err.println(msg);
            throw new SecurityException(msg);
        }
        if (!announcedGranted) {
            announcedGranted = true;
            System.out.println("LICENSE GRANTED: "
                + ((LicenseResult.Allowed) result).reason());
        }
    }

    /** Test-only: reset the JVM-wide cache so a test can re-exercise check(). */
    static void resetForTesting() {
        CACHE.clear();
        announcedCiMock = false;
        announcedGranted = false;
    }

    /** Test-only: number of cached fingerprints, for asserting cache-hit behaviour. */
    static int cacheSize() {
        return CACHE.size();
    }

    private record Fingerprint(
        String keygenAccountId,
        String keygenApiKey,
        String keygenProductId,
        String lemonSqueezyStore,
        String licenseKey,
        boolean licenseMockMode
    ) {
        static Fingerprint from(AsyncTestConfig c) {
            return new Fingerprint(
                resolve(c.keygenAccountId,   "keygen.account.id", "dummy-account"),
                resolve(c.keygenApiKey,      "keygen.api.key",    null),
                resolve(c.keygenProductId,   "keygen.product.id", "dummy-prod"),
                resolve(c.lemonSqueezyStore, "ls.store.subdomain", null),
                resolve(c.licenseKey,        "license.key",        null),
                c.licenseMockMode
            );
        }
        private static String resolve(String configValue, String sysProp, String def) {
            if (configValue != null && !configValue.isEmpty()) return configValue;
            return System.getProperty(sysProp, def);
        }
    }
}
