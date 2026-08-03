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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     * Classpath resource listing the built-in factories, one class name per line.
     *
     * <p>Deliberately not a {@code META-INF/services} file. Those factories construct <em>fresh</em>
     * legacy detector instances, disconnected from the ones the running test actually records into
     * (which live on the {@code AsyncTestContext}'s legacy registry). They exist so that every
     * {@link DetectorType} is addressable through this registry, not as a second live detection
     * path, so runtime discovery should not pay to load them. See
     * {@link #buildExternal(AsyncTestConfig)}.
     */
    private static final String BUILT_IN_FACTORY_RESOURCE =
            "META-INF/async-test/builtin-detector-factories";

    /**
     * Build a registry for the given config: every built-in factory plus every third-party
     * {@link DetectorFactory} on the classpath, filtered by
     * {@link DetectorFactory#isEnabledFor(AsyncTestConfig)} and instantiated.
     *
     * <p>This is the addressability view, used to prove every {@link DetectorType} is reachable
     * through the SPI. It is not the path the runner takes: see
     * {@link #buildExternal(AsyncTestConfig)}.
     */
    public static DetectorRegistry build(AsyncTestConfig config) {
        Map<DetectorType, Detector> detectors = new EnumMap<>(DetectorType.class);
        addEnabled(builtInFactories(), config, detectors);
        addEnabled(externalFactories(), config, detectors);
        return new DetectorRegistry(detectors);
    }

    /**
     * Build a registry containing only <em>third-party</em> detectors.
     *
     * <p>This is the registry the runner installs alongside the legacy one: the legacy registry
     * already owns the built-in detectors (and holds the very instances user code records into),
     * so including their bridge factories here would allocate ~127 duplicate detectors per test
     * that observe nothing. Everything else on the classpath is a user-supplied detector whose
     * findings must reach the reports and the {@code failOn} gate, which is what makes the
     * published SPI more than documentation.
     *
     * <p><strong>Why the built-ins are not in {@code META-INF/services}.</strong> They used to be,
     * and this method filtered them out by package name. Filtering was not free: {@code
     * ServiceLoader} has to load a provider class before it can report that provider's type, so
     * every construction paid to load 127 classes and then discarded all of them. Measured cold in
     * a fresh JVM, that was ~383 ms, of which ~340 ms was the built-ins; with {@code forkEvery = 1}
     * it was charged once per test class, and it returned nothing in the common case where no
     * third-party detector is installed. They now live in
     * {@code META-INF/async-test/builtin-detector-factories}, which only {@link
     * #build(AsyncTestConfig)} reads, so runtime discovery sees only genuine third-party providers.
     *
     * @since 1.7.0
     */
    public static DetectorRegistry buildExternal(AsyncTestConfig config) {
        Map<DetectorType, Detector> detectors = new EnumMap<>(DetectorType.class);
        addEnabled(externalFactories(), config, detectors);
        return new DetectorRegistry(detectors);
    }

    private static void addEnabled(List<DetectorFactory> factories, AsyncTestConfig config,
                                   Map<DetectorType, Detector> into) {
        for (DetectorFactory factory : factories) {
            if (factory.isEnabledFor(config)) {
                into.put(factory.type(), factory.create(config));
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
     * {@return the built-in factories listed in {@code META-INF/async-test/builtin-detector-factories}}
     *
     * <p>Read and instantiated reflectively rather than through {@link ServiceLoader}, so that
     * loading these classes is charged only to callers that actually want them. A missing or
     * unloadable entry is a build-time mistake rather than a runtime condition to tolerate:
     * {@code AllDetectorsSpiCoverageTest} fails on it, so it throws rather than degrading to a
     * silently smaller registry.
     */
    private static List<DetectorFactory> builtInFactories() {
        List<DetectorFactory> factories = new ArrayList<>();
        try (InputStream in = DetectorRegistry.class.getClassLoader()
                .getResourceAsStream(BUILT_IN_FACTORY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        BUILT_IN_FACTORY_RESOURCE + " is missing from the jar. Every DetectorType "
                                + "is expected to be addressable through this registry; without it "
                                + "build() silently returns only third-party detectors.");
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (name.isEmpty() || name.startsWith("#")) {
                        continue;
                    }
                    factories.add(instantiate(name));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + BUILT_IN_FACTORY_RESOURCE, e);
        }
        return factories;
    }

    private static DetectorFactory instantiate(String className) {
        try {
            return Class.forName(className)
                    .asSubclass(DetectorFactory.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "Built-in detector factory " + className + " could not be instantiated. It is "
                            + "listed in " + BUILT_IN_FACTORY_RESOURCE + ", so either the class was "
                            + "renamed without updating that file or it lost its no-argument "
                            + "constructor.", e);
        }
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
    public <T extends Detector> @Nullable T get(Class<T> detectorClass) {
        for (Detector d : byType.values()) {
            if (detectorClass.isInstance(d)) return (T) d;
        }
        return null;
    }

    /** Type-keyed lookup. */
    public @Nullable Detector get(DetectorType type) {
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
        for (Detector d : byType.values()) d.onTestStart();
    }
    /**
     * Fire on test end.
     */

    public void fireOnTestEnd() {
        for (Detector d : byType.values()) d.onTestEnd();
    }
}
