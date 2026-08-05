package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.common.license.LicenseConfig;
import se.deversity.common.license.LicenseGate;
import se.deversity.common.license.LicenseResult;
import se.deversity.common.license.lemonsqueezy.LemonSqueezyValidator;
import se.deversity.common.license.lemonsqueezy.LemonSqueezyValidator.EmailBinding;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;
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
        boolean isCi = System.getenv("GITHUB_ACTIONS") != null || System.getenv("CI") != null;
        boolean lemonSqueezy = fp.licenseProvider == LicenseConfig.Provider.LEMONSQUEEZY;

        // "Configured to validate for real" means something different per provider: Keygen
        // authenticates the caller with an API key, LemonSqueezy has no caller credential at all
        // and is scoped by store id plus the key under test. Reusing the Keygen test here would
        // auto-mock a correctly configured LemonSqueezy run in CI and announce GRANTED without
        // having validated anything.
        boolean hasCredentials = lemonSqueezy
            ? fp.lemonSqueezyStoreId != null && fp.licenseKey != null && !fp.licenseKey.isBlank()
            : fp.keygenApiKey != null && !fp.keygenApiKey.isBlank();

        boolean mock   = fp.licenseMockMode
                       || Boolean.getBoolean("license.mock.mode")
                       || (isCi && !hasCredentials);
        String licenseIdentity   = fp.licenseUserEmail;
        String keygenKeyForCheck = (fp.keygenApiKey == null) ? "dummy-key" : fp.keygenApiKey;

        LicenseConfig.Builder cfg = LicenseConfig.builder()
            .licenseProvider(fp.licenseProvider)
            .lemonSqueezyStoreSubdomain(fp.lemonSqueezyStore)
            .mockMode(mock);
        if (lemonSqueezy) {
            cfg.lemonSqueezyStoreId(fp.lemonSqueezyStoreId)
               .lemonSqueezyProductId(fp.lemonSqueezyProductId);
            if (fp.lemonSqueezyBaseUri != null) {
                cfg.lemonSqueezyBaseUri(URI.create(fp.lemonSqueezyBaseUri));
            }
            if (fp.lemonSqueezyEmailBinding != null) {
                cfg.lemonSqueezyEmailBinding(fp.lemonSqueezyEmailBinding);
            }
        } else {
            cfg.keygenAccountId(fp.keygenAccountId)
               .keygenApiKey(keygenKeyForCheck)
               .keygenProductId(fp.keygenProductId);
        }
        LicenseGate gate = LicenseGate.of(cfg.build());

        if (mock && isCi && !hasCredentials && !announcedCiMock) {
            announcedCiMock = true;
            log.info("LICENSE: Zero-Config CI mode active (Auto-Mocked)");
        }

        LicenseResult result = gate.check(licenseIdentity, fp.licenseKey);

        if (result instanceof LicenseResult.Denied denied) {
            String msg = "LICENSE DENIED: " + denied.reason()
                + (denied.message() != null ? " - " + denied.message() : "");
            String guidance = "\n  To run locally without a key: -Dlicense.mock.mode=true"
                + "\n  In CI (GITHUB_ACTIONS or CI env var set, no key): mock mode activates automatically."
                + "\n  To use a Keygen license: -Dlicense.key=<key> -Dlicense.user.email=<email>"
                + "\n  To use a LemonSqueezy license: -Dlicense.provider=lemonsqueezy"
                + " -Dls.store.id=<storeId> -Dlicense.key=<key> -Dlicense.user.email=<email>"
                + "\n  (the email must be the address the license was bought with)";
            log.error("{}{}", msg, guidance);
            throw new SecurityException(msg + guidance);
        }
        if (!announcedGranted) {
            announcedGranted = true;
            log.info("LICENSE GRANTED: {} provider={}",
                ((LicenseResult.Allowed) result).reason(), fp.licenseProvider);
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
        LicenseConfig.Provider licenseProvider,
        String keygenAccountId,
        @Nullable String keygenApiKey,
        String keygenProductId,
        @Nullable String lemonSqueezyStore,
        @Nullable Long lemonSqueezyStoreId,
        @Nullable Long lemonSqueezyProductId,
        @Nullable String lemonSqueezyBaseUri,
        @Nullable EmailBinding lemonSqueezyEmailBinding,
        @Nullable String licenseKey,
        String licenseUserEmail,
        boolean licenseMockMode
    ) {
        static Fingerprint from(AsyncTestConfig c) {
            return new Fingerprint(
                resolveProvider(),
                resolve(c.keygenAccountId,   "keygen.account.id", "dummy-account"),
                resolveOptional(c.keygenApiKey,      "keygen.api.key"),
                resolve(c.keygenProductId,   "keygen.product.id", "dummy-prod"),
                resolveOptional(c.lemonSqueezyStore, "ls.store.subdomain"),
                resolveLong("ls.store.id"),
                resolveLong("ls.product.id"),
                System.getProperty("ls.api.base.uri"),
                resolveEmailBinding(),
                resolveOptional(c.licenseKey,        "license.key"),
                System.getProperty("license.user.email", ""),
                c.licenseMockMode
            );
        }

        /**
         * Which service validates the key, from {@code -Dlicense.provider}. Defaults to Keygen,
         * so a run that names no provider behaves exactly as it did before LemonSqueezy support
         * existed. An unrecognised value is rejected rather than quietly falling back — a typo
         * that silently reverted to Keygen would look like a license failure, not a config error.
         */
        private static LicenseConfig.Provider resolveProvider() {
            String raw = System.getProperty("license.provider");
            if (raw == null || raw.isBlank()) return LicenseConfig.Provider.KEYGEN;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "keygen" -> LicenseConfig.Provider.KEYGEN;
                case "lemonsqueezy", "lemon-squeezy", "ls" -> LicenseConfig.Provider.LEMONSQUEEZY;
                default -> throw new IllegalArgumentException(
                    "Unknown license.provider '" + raw + "' (expected 'keygen' or 'lemonsqueezy')");
            };
        }

        /**
         * How a LemonSqueezy key is matched to the running user, from {@code -Dls.email.binding}.
         * {@code null} leaves the library default, which is {@code DOMAIN} — one company purchase
         * covering every developer on the buyer's email domain. {@code exact} narrows it to the
         * buying address alone, for per-seat licensing.
         */
        private static @Nullable EmailBinding resolveEmailBinding() {
            String raw = System.getProperty("ls.email.binding");
            if (raw == null || raw.isBlank()) return null;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "domain" -> EmailBinding.DOMAIN;
                case "exact"  -> EmailBinding.EXACT;
                default -> throw new IllegalArgumentException(
                    "Unknown ls.email.binding '" + raw + "' (expected 'domain' or 'exact')");
            };
        }

        /** Resolves a numeric system property, absent when unset. */
        private static @Nullable Long resolveLong(String sysProp) {
            String v = System.getProperty(sysProp);
            if (v == null || v.isBlank()) return null;
            try {
                return Long.valueOf(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    sysProp + " must be a number, got '" + v + "'", e);
            }
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
