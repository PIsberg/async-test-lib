package se.deversity.asynctest.spi;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SPI-driven registry that complements the legacy
 * {@code se.deversity.asynctest.DetectorRegistry}.
 *
 * <p>While the legacy registry hard-codes 111 detector wirings as fields and
 * if-blocks (one per type), this registry discovers detectors via
 * {@link ServiceLoader}, builds per-test instances from
 * {@link DetectorFactory#isEnabledFor(AsyncTestConfig) enabled} factories, and
 * exposes them via a single generic typed accessor.
 *
 * <p>Both registries currently coexist; the SPI is the path forward for new
 * detectors, the legacy registry preserves wiring for the existing 106 until
 * each is migrated.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@AIImmutable(note = "Effectively immutable after build() — the EnumMap is populated only in the private constructor and never mutated thereafter; safe to publish to multiple threads and read-only views over an EnumMap populated once at construction.")
@API(status = Status.STABLE)
public final class DetectorRegistry {

    private final Map<DetectorType, Detector> byType = new EnumMap<>(DetectorType.class);

    private DetectorRegistry(Map<DetectorType, Detector> detectors) {
        byType.putAll(detectors);
    }

    /**
     * Package of the built-in factories that merely bridge the legacy detectors
     * ({@code LegacyDetectorFactories}, {@code SharedMessageDigestDetectorFactory}).
     *
     * <p>Those factories construct <em>fresh</em> legacy detector instances, disconnected
     * from the ones the running test actually records into (which live on the
     * {@code AsyncTestContext}'s legacy registry). They exist so that every
     * {@link DetectorType} is addressable through this registry; they are not a second
     * live detection path. {@link #buildExternal(AsyncTestConfig)} therefore skips them.
     */
    private static final String BUILT_IN_FACTORY_PACKAGE = "se.deversity.asynctest.spi.adapters.";

    /**
     * Build a registry for the given config: discover all {@link DetectorFactory}
     * services on the classpath, filter by {@link DetectorFactory#isEnabledFor(AsyncTestConfig)},
     * and instantiate.
     */
    public static DetectorRegistry build(AsyncTestConfig config) {
        return build(config, false);
    }

    /**
     * Build a registry containing only <em>third-party</em> detectors — every discovered
     * {@link DetectorFactory} except the built-in legacy bridges in
     * {@code se.deversity.asynctest.spi.adapters}.
     *
     * <p>This is the registry the runner installs alongside the legacy one: the legacy
     * registry already owns the built-in detectors (and holds the very instances user code
     * records into), so including their bridge factories here would allocate ~120 duplicate
     * detectors per test that observe nothing. Everything else on the classpath is a
     * user-supplied detector whose findings must reach the reports and the {@code failOn}
     * gate — which is what makes the published SPI more than documentation.
     *
     * <p>Built-in factories are filtered by {@link ServiceLoader.Provider#type()}, before
     * {@link ServiceLoader.Provider#get()}, so they are never even instantiated.
     *
     * @since 1.7.0
     */
    public static DetectorRegistry buildExternal(AsyncTestConfig config) {
        return build(config, true);
    }

    private static DetectorRegistry build(AsyncTestConfig config, boolean externalOnly) {
        Map<DetectorType, Detector> detectors = new EnumMap<>(DetectorType.class);
        for (ServiceLoader.Provider<DetectorFactory> provider
                : ServiceLoader.load(DetectorFactory.class).stream().toList()) {
            if (externalOnly && provider.type().getName().startsWith(BUILT_IN_FACTORY_PACKAGE)) {
                continue;
            }
            DetectorFactory f = provider.get();
            if (f.isEnabledFor(config)) {
                detectors.put(f.type(), f.create(config));
            }
        }
        return new DetectorRegistry(detectors);
    }

    /** {@return {@code true} when no detector is active in this registry} */
    public boolean isEmpty() {
        return byType.isEmpty();
    }

    /**
     * Typed lookup. Returns null when the detector is not active for this test.
     *
     * <p>Calls into user code should treat null as "feature off" rather than an
     * error — matches the behavior of the legacy {@code AsyncTestContext.require}
     * accessors but without the exception.
     */
    @SuppressWarnings("unchecked")
    public <T extends Detector> T get(Class<T> detectorClass) {
        for (Detector d : byType.values()) {
            if (detectorClass.isInstance(d)) return (T) d;
        }
        return null;
    }

    /** Type-keyed lookup. */
    public Detector get(DetectorType type) {
        return byType.get(type);
    }

    /** All active detectors (snapshot). */
    public List<Detector> all() {
        return new ArrayList<>(byType.values());
    }

    /** Aggregated violations from every active detector for the current round. */
    @AIIdempotent(reason = "Each Detector.analyze() must return the same violations for the same observed state (the SPI contract). Calling analyzeAll() N times on a quiescent registry yields N identical lists; do not introduce stateful side-effects in analyze().")
    public List<Violation> analyzeAll() {
        List<Violation> out = new ArrayList<>();
        for (Detector d : byType.values()) {
            try {
                out.addAll(d.analyze());
            } catch (RuntimeException | StackOverflowError e) {
                // Contain the failure. Detectors arrive here through the public SPI, so one
                // of them throwing must not discard the violations already collected nor skip
                // every detector after it in iteration order.
                System.err.println("[AsyncTest] Detector " + d.getClass().getSimpleName()
                    + " failed during analysis and was skipped: " + e);
            }
        }
        return out;
    }

    public void fireOnTestStart() {
        for (Detector d : byType.values()) d.onTestStart();
    }

    public void fireOnTestEnd() {
        for (Detector d : byType.values()) d.onTestEnd();
    }
}
