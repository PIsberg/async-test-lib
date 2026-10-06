package se.deversity.asynctest.spi;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The detectors a user adds: every {@link DetectorFactory} on the classpath, discovered through
 * {@link ServiceLoader}, built per test when {@link DetectorFactory#isEnabledFor(AsyncTestConfig)
 * enabled}, and keyed by {@link Detector#id() id} (#919).
 *
 * <p>The built-in detectors are not here. The runner's own
 * {@code se.deversity.asynctest.DetectorRegistry} builds and analyses them, and holds the very
 * instances user code records into. Until 1.13.0 this class also offered {@code build(config)}, a
 * view that added a bridge factory per built-in detector, each constructing a fresh instance that
 * observed nothing; only tests called it, and every new detector paid two edit sites for it. 1.13.0
 * deleted that path (#922): this registry is the third-party extension point and nothing else.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@AIImmutable(note = "Effectively immutable after buildExternal() — the id-keyed map is populated only in the private constructor and never mutated thereafter; safe to publish to multiple threads and read-only views over a map populated once at construction.")
@API(status = Status.STABLE)
public final class DetectorRegistry {

    private final Map<String, Detector> byId = new LinkedHashMap<>();

    private DetectorRegistry(Map<String, Detector> detectors) {
        byId.putAll(detectors);
    }

    /**
     * Build a registry of the <em>third-party</em> detectors enabled for {@code config}.
     *
     * <p>This is the registry the runner installs beside its own. Every factory on the classpath is
     * a user-supplied detector whose findings must reach the reports and the {@code failOn} gate,
     * which is what makes the published SPI more than documentation. The library registers no
     * factory of its own, so in the common case, with no third-party detector installed,
     * {@link ServiceLoader} loads nothing.
     *
     * @since 1.7.0
     *
     * @param config the configuration deciding which factories report themselves as enabled
     * @return a registry holding only the enabled third-party detectors
     */
    public static DetectorRegistry buildExternal(AsyncTestConfig config) {
        Map<String, Detector> detectors = new LinkedHashMap<>();
        addEnabled(externalFactories(), config, detectors);
        return new DetectorRegistry(detectors);
    }

    private static void addEnabled(List<DetectorFactory> factories, AsyncTestConfig config,
                                   Map<String, Detector> into) {
        for (DetectorFactory factory : factories) {
            String id = factory.id();
            // An id the test excludes is never built, whatever the factory's own answer: a
            // third-party isEnabledFor that ignores the config cannot defeat excludeIds (#919).
            if (!config.excludedIds().contains(id) && factory.isEnabledFor(config)) {
                into.put(id, factory.create(config));
            }
        }
    }

    /** {@return the third-party factories on the classpath, discovered by {@link ServiceLoader}} */
    private static List<DetectorFactory> externalFactories() {
        List<DetectorFactory> factories = new ArrayList<>();
        for (DetectorFactory factory : ServiceLoader.load(DetectorFactory.class)) {
            factories.add(factory);
        }
        return factories;
    }

    /**
     * {@return {@code true} when no detector is active in this registry}
     */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /**
     * Typed lookup. Returns null when the detector is not active for this test.
     *
     * <p>Calls into user code should treat null as "feature off" rather than an
     * error — matches the behavior of the legacy {@code AsyncTestContext.require}
     * accessors but without the exception.
     *
     * @param <T>            the detector type being looked up
     * @param detectorClass  the class to match against the active detectors
     * @return the active detector of that class, or {@code null} when it is not enabled
     */
    @SuppressWarnings("unchecked")
    public <T extends Detector> @Nullable T get(Class<T> detectorClass) {
        for (Detector d : byId.values()) {
            if (detectorClass.isInstance(d)) return (T) d;
        }
        return null;
    }

    /**
     * Type-keyed lookup: the detector whose id is {@code type.name()}.
     *
     * @param type the detector to look up
     * @return the active detector for that type, or {@code null} when it is not enabled
     */
    public @Nullable Detector get(DetectorType type) {
        return byId.get(type.name());
    }

    /**
     * Id-keyed lookup, for a detector with an identity of its own as well as a built-in one.
     *
     * @param id the {@link Detector#id()} to look up
     * @return the active detector with that id, or {@code null} when it is not enabled
     * @since 1.13.0
     */
    public @Nullable Detector get(String id) {
        return byId.get(id);
    }

    /**
     * All active detectors (snapshot).
     *
     * @return the active detectors, in the order they were discovered
     */
    public List<Detector> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Aggregated violations from every active detector for the current round.
     *
     * @return the violations reported by every active detector
     */
    @AIIdempotent(reason = "Each Detector.analyze() must return the same violations for the same observed state (the SPI contract). Calling analyzeAll() N times on a quiescent registry yields N identical lists; do not introduce stateful side-effects in analyze().")
    public List<Violation> analyzeAll() {
        List<Violation> out = new ArrayList<>();
        for (Detector d : byId.values()) {
            try {
                out.addAll(d.analyze());
            } catch (RuntimeException | StackOverflowError e) {
                // Contain the failure. Detectors arrive here through the public SPI, so one
                // of them throwing must not discard the violations already collected nor skip
                // every detector after it in iteration order. Strict mode (this project's own
                // test config) turns the contained failure into a build failure instead —
                // see DetectorFailurePolicy.
                se.deversity.asynctest.DetectorFailurePolicy
                    .detectorFailed(d.getClass().getSimpleName(), e);
            }
        }
        return out;
    }
    /**
     * Fire on test start.
     */
    public void fireOnTestStart() {
        for (Detector d : byId.values()) d.onTestStart();
    }
    /**
     * Fire on test end.
     */
    public void fireOnTestEnd() {
        for (Detector d : byId.values()) d.onTestEnd();
    }
}
