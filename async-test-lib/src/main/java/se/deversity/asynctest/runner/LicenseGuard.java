package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.common.license.LicenseConfig;
import se.deversity.common.license.LicenseGate;
import se.deversity.common.license.LicenseResult;
import se.deversity.common.license.lemonsqueezy.LemonSqueezyValidator.EmailBinding;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
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
 *
 * <p>The decision order, first match wins:
 *
 * <ol>
 *   <li><b>Mock</b> — {@code license.mock.mode} (explicit, or auto-activated in CI when no
 *       credentials are asserted): granted without validation.</li>
 *   <li><b>Offline file</b> — {@code -Dlicense.file}: verified locally against the vendor's
 *       embedded Ed25519 key by {@link OfflineLicense}, no network. The sanctioned path for
 *       air-gapped and egress-blocked environments. A present-but-invalid file fails closed;
 *       it never falls through to online validation.</li>
 *   <li><b>Cached validation</b> — a successful online validation of this exact configuration
 *       within {@code license.cache.ttl.hours} (default 24) skips revalidation, so
 *       {@code forkEvery = 1} suites do not make one licensing API call per test-class JVM.</li>
 *   <li><b>Online validation</b> — the provider decides; definitive rejections
 *       (not found, expired, suspended, wrong scope) always fail the build.</li>
 *   <li><b>Outage grace</b> — a {@code NETWORK_ERROR} under the default
 *       {@code license.network.mode=grace} is forgiven only when the provider host is
 *       connection-level unreachable (probed) or this configuration has validated successfully
 *       before (disk record). {@code strict} restores unconditional fail-closed.</li>
 * </ol>
 *
 * <p><b>Security analysis of grace.</b> common-license-lib maps transport failures <em>and</em>
 * provider error statuses (401/429/5xx) to the single reason {@code NETWORK_ERROR}. Granting on
 * that reason unconditionally would re-open the silent-grant defect pinned by
 * {@code LicenseGuardTest}: fabricated credentials would pass wherever the provider answers with
 * an error. The two grace conditions keep the property that matters — <em>credentials the
 * provider actively rejected, and credentials never seen to validate against a reachable
 * provider, both fail the build</em> — while a licensing-provider outage stops failing paying
 * customers' builds. An attacker who deliberately blackholes the provider host can run unlicensed
 * with a fabricated key; that is accepted, because the documented
 * {@code -Dlicense.mock.mode=true} bypass is already one property away and the gate is a
 * compliance aid, not DRM (see docs/LICENSING.md).
 */
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.OTHER,
    note = "ConcurrentHashMap.computeIfAbsent guarantees at-most-once gate execution per fingerprint under contention; volatile announce flags collapse the GRANTED/CI/grace banners to once-per-JVM."
)
@AISecure(aspect = "authorization")
public final class LicenseGuard {

    private static final Logger log = LoggerFactory.getLogger(LicenseGuard.class);
    private static final ConcurrentMap<Fingerprint, Boolean> CACHE = new ConcurrentHashMap<>();

    /**
     * Placeholder provider coordinates. They are the defaults on purpose — the account and
     * product ids belong to the buyer's configuration, not to this artifact, so the library
     * ships without them. What they must never do is combine with a supplied licence key to
     * produce a silent grant, which is what {@link #requireProviderCoordinates} prevents.
     */
    private static final String DUMMY_KEYGEN_ACCOUNT = "dummy-account";
    private static final String DUMMY_KEYGEN_PRODUCT = "dummy-prod";
    private static volatile boolean announcedCiMock = false;
    private static volatile boolean announcedGranted = false;
    private static volatile boolean announcedGraceGrant = false;
    private static volatile boolean announcedNoncommercial = false;

    /**
     * What a run that proceeds without a validated commercial licence is told, once per JVM.
     *
     * <p>Printed on the report channel ({@code System.err}, where findings go) rather than
     * through SLF4J: a consumer with no logging binding sees nothing from the logger, and this is
     * the one message the library must not lose. The terms are the terms of the artifact's own
     * {@code LICENSE} file; the notice does not add a restriction, it repeats one that the mock
     * and free-mail paths would otherwise let a commercial user forget. It is deliberately not
     * suppressible by a flag: a licence key suppresses it, which is the point.
     */
    static final String NONCOMMERCIAL_NOTICE = """
        [AsyncTest] async-test-lib is free for non-commercial use under the PolyForm Noncommercial License 1.0.0.
        [AsyncTest] Use by or for a business, including in a company's CI, requires a commercial license.
        [AsyncTest] Pricing and purchase: https://deversity.se/pricing.html   Contact: peter.isberg@deversity.se\
        """;

    private LicenseGuard() {}

    /** How a {@code NETWORK_ERROR} from the validator is treated; see the class javadoc. */
    private enum NetworkMode { GRACE, STRICT }

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
        boolean offline = notBlank(fp.licenseFile);

        // An offline file is a commercial assertion like a key: its presence must keep CI from
        // auto-mocking, or a bad file would be announced GRANTED exactly where builds really run.
        boolean hasCredentials = offline || assertsCommercialLicence(
                lemonSqueezy, fp.licenseKey, fp.keygenApiKey, fp.lemonSqueezyStoreId);

        boolean mock   = fp.licenseMockMode
                       || Boolean.getBoolean("license.mock.mode")
                       || (isCi && !hasCredentials);

        if (!mock && offline) {
            OfflineLicense.Grant grant = OfflineLicense.verify(
                Objects.requireNonNull(fp.licenseFile), fp.licenseUserEmail);
            if (!announcedGranted) {
                announcedGranted = true;
                log.info("LICENSE GRANTED: OFFLINE_LICENSE licensee={} expires={}",
                    grant.licensee(), grant.expires());
            }
            return;
        }

        // A key supplied against placeholder provider coordinates cannot be validated by anyone.
        // Granting in that state is the worst of the three outcomes: the customer believes they
        // are licensed, the check never ran, and an invalid key is indistinguishable from a valid
        // one. Fail closed and name the missing property.
        if (!mock) requireProviderCoordinates(fp, lemonSqueezy);

        String cacheHash = null;
        if (!mock && hasCredentials) {
            cacheHash = LicenseValidationCache.hash(cacheKeyMaterial(fp));
            if (LicenseValidationCache.isFresh(cacheHash)) {
                if (!announcedGranted) {
                    announcedGranted = true;
                    log.info("LICENSE GRANTED: CACHED_VALIDATION provider={} (revalidates online "
                        + "when the license.cache.ttl.hours window lapses)", fp.licenseProvider);
                }
                return;
            }
        }

        String licenseIdentity   = fp.licenseUserEmail;
        // Customers are never given an API token: Keygen's validate-key is a public endpoint,
        // but a placeholder bearer is rejected with 401 before the key is even evaluated, which
        // denied every legitimate commercial run. null means "send no Authorization header".
        String keygenKeyForCheck = fp.keygenApiKey;

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
            if (fp.keygenBaseUri != null) {
                cfg.keygenBaseUri(URI.create(fp.keygenBaseUri));
            }
        }
        LicenseGate gate = LicenseGate.of(cfg.build());

        if (mock && isCi && !hasCredentials && !announcedCiMock) {
            announcedCiMock = true;
            log.info("LICENSE: Zero-Config CI mode active (Auto-Mocked)");
        }

        LicenseResult result = gate.check(licenseIdentity, fp.licenseKey);

        if (result instanceof LicenseResult.Denied denied) {
            if (denied.reason() == LicenseResult.DeniedReason.NETWORK_ERROR
                && fp.networkMode == NetworkMode.GRACE
                && hasCredentials
                && !mock) {
                boolean validatedBefore = cacheHash != null && LicenseValidationCache.hasRecord(cacheHash);
                if (validatedBefore || validatorUnreachable(fp, lemonSqueezy)) {
                    if (!announcedGraceGrant) {
                        announcedGraceGrant = true;
                        log.warn("LICENSE: validator unavailable ({}); proceeding under the grace "
                            + "policy because {}. Definitive rejections still fail the build. "
                            + "To fail closed on outages instead: -Dlicense.network.mode=strict. "
                            + "For permanently offline or egress-blocked CI use an offline license "
                            + "file: -Dlicense.file=<path> (docs/LICENSING.md).",
                            denied.message(),
                            validatedBefore
                                ? "this configuration has validated successfully before"
                                : "the provider host is unreachable");
                    }
                    return;
                }
            }
            String msg = "LICENSE DENIED: " + denied.reason()
                + (denied.message() != null ? " - " + denied.message() : "");
            String guidance = "\n  To run locally without a key: -Dlicense.mock.mode=true"
                + "\n  In CI (GITHUB_ACTIONS or CI env var set, no key): mock mode activates automatically."
                + "\n  To use a Keygen license: -Dlicense.key=<key> -Dlicense.user.email=<email>"
                + "\n  To use a LemonSqueezy license: -Dlicense.provider=lemonsqueezy"
                + " -Dls.store.id=<storeId> -Dlicense.key=<key> -Dlicense.user.email=<email>"
                + "\n  (the email must be the address the license was bought with)"
                + "\n  Air-gapped or egress-blocked CI: use an offline license file: -Dlicense.file=<path>"
                + "\n  Validator-outage policy: -Dlicense.network.mode=grace|strict (default grace)";
            log.error("{}{}", msg, guidance);
            throw new SecurityException(msg + guidance);
        }
        LicenseResult.Allowed allowed = (LicenseResult.Allowed) result;
        if (allowed.reason() == LicenseResult.AllowedReason.LICENSE_VALID && cacheHash != null) {
            LicenseValidationCache.record(cacheHash);
        }
        if (!announcedGranted) {
            announcedGranted = true;
            log.info("LICENSE GRANTED: {} provider={}", allowed.reason(), fp.licenseProvider);
        }
        if (allowed.reason() != LicenseResult.AllowedReason.LICENSE_VALID) {
            announceNoncommercial();
        }
    }

    /**
     * Prints {@link #NONCOMMERCIAL_NOTICE} once per JVM. Reached only by grants that did not
     * validate a commercial licence: mock mode (explicit or CI auto-mock) and free-mail addresses.
     * The offline-file, cached-validation and grace paths return before this and are silent, as is
     * a {@code LICENSE_VALID} grant, so a paying customer never sees the notice.
     */
    private static void announceNoncommercial() {
        if (announcedNoncommercial) return;
        announcedNoncommercial = true;
        System.err.println(NONCOMMERCIAL_NOTICE);
    }

    /**
     * Whether this run is asserting a commercial licence, and therefore must be validated for
     * real rather than auto-mocked in CI.
     *
     * <p>For both providers the answer is the licence key, not a caller credential. Keying this
     * off {@code keygen.api.key} was wrong in the one case that matters most: customers are never
     * issued an API token (see {@code keygenKeyForCheck} in {@link #performCheck}), so every
     * paying customer's CI run reported "no credentials", auto-mocked, and announced GRANTED
     * without the key they had just paid for ever being checked. An expired key, a revoked key
     * and a fabricated key all passed, in the environment where builds actually run. An operator
     * token still counts when one is present, which is what the library's own tooling uses.
     *
     * <p>Package-private so the decision can be tested directly. The surrounding path depends on
     * environment variables that a unit test cannot set for its own JVM.
     *
     * @param lemonSqueezy       whether the LemonSqueezy provider is selected
     * @param licenseKey         the resolved licence key, if any
     * @param keygenApiKey       the resolved Keygen operator token, if any
     * @param lemonSqueezyStoreId the resolved LemonSqueezy store id, if any
     * @return {@code true} when the run must be validated against the provider
     */
    static boolean assertsCommercialLicence(boolean lemonSqueezy,
                                            @Nullable String licenseKey,
                                            @Nullable String keygenApiKey,
                                            @Nullable Long lemonSqueezyStoreId) {
        return lemonSqueezy
            ? lemonSqueezyStoreId != null && notBlank(licenseKey)
            : notBlank(licenseKey) || notBlank(keygenApiKey);
    }

    /**
     * Rejects a run that supplies a licence key without the provider coordinates needed to check
     * it, and says which property is missing.
     *
     * <p>This is a diagnosis fix rather than a security fix: measured against the provider, a key
     * sent to the {@code dummy-account} placeholder is already refused, with
     * {@code LICENSE DENIED: LICENSE_NOT_FOUND}. That message is actively misleading — it reads
     * as "your key is invalid" when the truth is "you never told the library which account to ask",
     * and it sends a paying customer to support about a key that is fine. Failing earlier, with
     * the missing property named, costs nothing: every run rejected here would have been rejected
     * a moment later anyway.
     *
     * <p>Runs with no key at all are untouched and still follow the documented no-key path.
     *
     * @param fp           the resolved licensing inputs for this run
     * @param lemonSqueezy whether the LemonSqueezy provider is selected
     * @throws SecurityException if a key is present but the provider is not fully configured
     */
    private static void requireProviderCoordinates(Fingerprint fp, boolean lemonSqueezy) {
        if (!notBlank(fp.licenseKey)) return;   // no commercial claim to verify

        String missing = null;
        if (lemonSqueezy) {
            if (fp.lemonSqueezyStoreId == null) missing = "-Dls.store.id=<storeId>";
        } else if (DUMMY_KEYGEN_ACCOUNT.equals(fp.keygenAccountId)) {
            missing = "-Dkeygen.account.id=<accountId>";
        } else if (DUMMY_KEYGEN_PRODUCT.equals(fp.keygenProductId)) {
            missing = "-Dkeygen.product.id=<productId>";
        }
        if (missing == null) return;

        String msg = "LICENSE MISCONFIGURED: a license key was supplied but " + missing
            + " was not, so the key cannot be validated against anything."
            + "\n  The account and product ids come with your license; see docs/LICENSING.md."
            + "\n  To run without a key while evaluating: -Dlicense.mock.mode=true";
        log.error("{}", msg);
        throw new SecurityException(msg);
    }

    /**
     * Whether the configured provider host is unreachable at the connection level, which is what
     * separates "the licensing provider is down or blocked" from "the provider answered and
     * rejected this run". Any HTTP answer, whatever the status, counts as reachable: a host that
     * can say 401 or 500 could have said no to these credentials, so grace must not apply on the
     * probe's account. Runs at most once per fingerprint per JVM, and only after a
     * {@code NETWORK_ERROR} denial, so the happy path never pays for it.
     */
    private static boolean validatorUnreachable(Fingerprint fp, boolean lemonSqueezy) {
        String base;
        if (lemonSqueezy) {
            base = fp.lemonSqueezyBaseUri != null ? fp.lemonSqueezyBaseUri : "https://api.lemonsqueezy.com";
        } else {
            base = fp.keygenBaseUri != null ? fp.keygenBaseUri : "https://api.keygen.sh";
        }
        try {
            HttpClient probeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            HttpRequest probe = HttpRequest.newBuilder(URI.create(base).resolve("/"))
                .timeout(Duration.ofSeconds(3))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            probeClient.send(probe, HttpResponse.BodyHandlers.discarding());
            return false;
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (IllegalArgumentException e) {
            // A base URI too malformed to probe: let the original denial stand.
            return false;
        }
    }

    /**
     * The identity of a validation for the cross-JVM cache: provider coordinates, key and user,
     * nothing else. Policy knobs (network mode, cache TTL, mock) are deliberately excluded so
     * flipping them neither forges nor discards the record of a successful validation. Only the
     * SHA-256 of this string ever reaches disk.
     */
    private static String cacheKeyMaterial(Fingerprint fp) {
        return String.join(" ",
            String.valueOf(fp.licenseProvider),
            fp.keygenAccountId,
            String.valueOf(fp.keygenApiKey),
            fp.keygenProductId,
            String.valueOf(fp.keygenBaseUri),
            String.valueOf(fp.lemonSqueezyStore),
            String.valueOf(fp.lemonSqueezyStoreId),
            String.valueOf(fp.lemonSqueezyProductId),
            String.valueOf(fp.lemonSqueezyBaseUri),
            String.valueOf(fp.lemonSqueezyEmailBinding),
            String.valueOf(fp.licenseKey),
            fp.licenseUserEmail);
    }

    /**
     * {@return whether {@code s} is a non-null, non-blank string}
     *
     * @param s the value to test
     */
    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    /** Test-only: reset the JVM-wide cache so a test can re-exercise check(). */
    static void resetForTesting() {
        CACHE.clear();
        announcedCiMock = false;
        announcedGranted = false;
        announcedGraceGrant = false;
        announcedNoncommercial = false;
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
        @Nullable String keygenBaseUri,
        @Nullable String lemonSqueezyStore,
        @Nullable Long lemonSqueezyStoreId,
        @Nullable Long lemonSqueezyProductId,
        @Nullable String lemonSqueezyBaseUri,
        @Nullable EmailBinding lemonSqueezyEmailBinding,
        @Nullable String licenseKey,
        String licenseUserEmail,
        @Nullable String licenseFile,
        NetworkMode networkMode,
        boolean licenseMockMode
    ) {
        static Fingerprint from(AsyncTestConfig c) {
            return new Fingerprint(
                resolveProvider(),
                resolve(c.keygenAccountId,   "keygen.account.id", DUMMY_KEYGEN_ACCOUNT),
                resolveOptional(c.keygenApiKey,      "keygen.api.key"),
                resolve(c.keygenProductId,   "keygen.product.id", DUMMY_KEYGEN_PRODUCT),
                System.getProperty("keygen.base.uri"),
                resolveOptional(c.lemonSqueezyStore, "ls.store.subdomain"),
                resolveLong("ls.store.id"),
                resolveLong("ls.product.id"),
                System.getProperty("ls.api.base.uri"),
                resolveEmailBinding(),
                resolveOptional(c.licenseKey,        "license.key"),
                System.getProperty("license.user.email", ""),
                System.getProperty("license.file"),
                resolveNetworkMode(),
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
            return switch (asciiLower(raw.trim())) {
                case "keygen" -> LicenseConfig.Provider.KEYGEN;
                case "lemonsqueezy", "lemon-squeezy", "ls" -> LicenseConfig.Provider.LEMONSQUEEZY;
                default -> throw new IllegalArgumentException(
                    "Unknown license.provider '" + raw + "' (expected 'keygen' or 'lemonsqueezy')");
            };
        }

        /**
         * How a {@code NETWORK_ERROR} is treated, from {@code -Dlicense.network.mode}. The
         * default is {@code grace}: an unreachable validator must not fail a licensed build (see
         * the class javadoc for exactly when grace applies and why it cannot grant rejected
         * credentials). {@code strict} is the pre-1.9.1 behaviour: any validation failure fails
         * the build. An unrecognised value is rejected for the same reason a provider typo is.
         */
        private static NetworkMode resolveNetworkMode() {
            String raw = System.getProperty("license.network.mode");
            if (raw == null || raw.isBlank()) return NetworkMode.GRACE;
            return switch (asciiLower(raw.trim())) {
                case "grace"  -> NetworkMode.GRACE;
                case "strict" -> NetworkMode.STRICT;
                default -> throw new IllegalArgumentException(
                    "Unknown license.network.mode '" + raw + "' (expected 'grace' or 'strict')");
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
            return switch (asciiLower(raw.trim())) {
                case "domain" -> EmailBinding.DOMAIN;
                case "exact"  -> EmailBinding.EXACT;
                default -> throw new IllegalArgumentException(
                    "Unknown ls.email.binding '" + raw + "' (expected 'domain' or 'exact')");
            };
        }

        /**
         * ASCII-only lower-casing, used to match config keywords case-insensitively.
         *
         * <p>Deliberately not {@code String.toLowerCase}, even with {@code Locale.ROOT}. Unicode
         * case mapping folds characters outside ASCII onto ASCII ones — the Kelvin sign lowercases
         * to {@code k}, and dotted-I forms produce an {@code i} plus a combining mark — so a
         * value that is not the keyword can be mapped onto it. These two properties choose which
         * validator authorises a run, so the mapping must not be able to invent a match that the
         * literal text does not contain. Restricting the fold to {@code A-Z} makes the comparison
         * exactly "the same ASCII word, any case", and anything else falls to the default branch
         * and throws.
         */
        private static String asciiLower(String s) {
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                out.append(c >= 'A' && c <= 'Z' ? (char) (c - 'A' + 'a') : c);
            }
            return out.toString();
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
