package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.common.license.LicenseConfig;
import se.deversity.common.license.LicenseGate;
import se.deversity.common.license.LicenseResult;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.OTHER,
    note = "ConcurrentHashMap.computeIfAbsent guarantees at-most-once gate execution per fingerprint under contention; volatile announce flags collapse the GRANTED/CI banner to once-per-JVM."
)
@AISecure(aspect = "authorization")
public final class LicenseGuard {

    private static final Logger log = LoggerFactory.getLogger(LicenseGuard.class);
    private static final ConcurrentMap<Fingerprint, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean announcedCiMock = false;
    private static volatile boolean announcedGranted = false;

    private LicenseGuard() {}

    /**
     * Validates the license for the given config. Throws {@link SecurityException}
     * if denied. Subsequent calls with the same fingerprint return immediately.
     *
     * @param config the configuration whose fingerprint keys the cached decision
     */
    @AIIdempotent(reason = "ConcurrentHashMap.computeIfAbsent guarantees the underlying gate.check fires at most once per Fingerprint; repeat calls return immediately. Denied results consistently throw SecurityException for the same fingerprint.")
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
        String licenseIdentity   = fp.licenseUserEmail;
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
            log.info("LICENSE: Zero-Config CI mode active (Auto-Mocked)");
        }

        LicenseResult result = gate.check(licenseIdentity, fp.licenseKey);

        if (result instanceof LicenseResult.Denied denied) {
            String msg = "LICENSE DENIED: " + denied.reason()
                + (denied.message() != null ? " - " + denied.message() : "");
            String guidance = "\n  To run locally without a key: -Dlicense.mock.mode=true"
                + "\n  In CI (GITHUB_ACTIONS or CI env var set, no key): mock mode activates automatically."
                + "\n  To use a real license: -Dlicense.key=<key> -Dlicense.user.email=<email>";
            log.error("{}{}", msg, guidance);
            throw new SecurityException(msg + guidance);
        }
        if (!announcedGranted) {
            announcedGranted = true;
            log.info("LICENSE GRANTED: {}", ((LicenseResult.Allowed) result).reason());
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
        @Nullable String keygenApiKey,
        String keygenProductId,
        @Nullable String lemonSqueezyStore,
        @Nullable String licenseKey,
        String licenseUserEmail,
        boolean licenseMockMode
    ) {
        static Fingerprint from(AsyncTestConfig c) {
            return new Fingerprint(
                resolve(c.keygenAccountId,   "keygen.account.id", "dummy-account"),
                resolveOptional(c.keygenApiKey,      "keygen.api.key"),
                resolve(c.keygenProductId,   "keygen.product.id", "dummy-prod"),
                resolveOptional(c.lemonSqueezyStore, "ls.store.subdomain"),
                resolveOptional(c.licenseKey,        "license.key"),
                System.getProperty("license.user.email", ""),
                c.licenseMockMode
            );
        }
        /**
         * Resolves a fingerprint component that always ends up with a value: the config, else
         * the system property, else {@code def}. Split from {@link #resolveOptional} because the
         * two call shapes have different nullness — the components with a dummy default can never
         * be null, and one signature covering both made every fingerprint field look nullable.
         */
        private static String resolve(@Nullable String configValue, String sysProp, String def) {
            String resolved = resolveOptional(configValue, sysProp);
            return resolved != null ? resolved : def;
        }

        /** Resolves a fingerprint component that is absent when neither source supplies it. */
        private static @Nullable String resolveOptional(@Nullable String configValue,
                                                        String sysProp) {
            if (configValue != null && !configValue.isEmpty()) return configValue;
            return System.getProperty(sysProp);
        }
    }
}
