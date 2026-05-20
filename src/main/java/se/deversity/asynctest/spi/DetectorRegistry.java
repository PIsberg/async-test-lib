package se.deversity.asynctest.spi;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;
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
 * <p>While the legacy registry hard-codes 90+ detector wirings as fields and
 * if-blocks (one per type), this registry discovers detectors via
 * {@link ServiceLoader}, builds per-test instances from
 * {@link DetectorFactory#isEnabledFor(AsyncTestConfig) enabled} factories, and
 * exposes them via a single generic typed accessor.
 *
 * <p>Both registries currently coexist; the SPI is the path forward for new
 * detectors, the legacy registry preserves wiring for the existing 90+ until
 * each is migrated.
 *
 * @since 1.0.0
 */
@AIPublicAPI
public final class DetectorRegistry {

    private final Map<DetectorType, Detector> byType = new EnumMap<>(DetectorType.class);

    private DetectorRegistry(Map<DetectorType, Detector> detectors) {
        byType.putAll(detectors);
    }

    /**
     * Build a registry for the given config: discover all {@link DetectorFactory}
     * services on the classpath, filter by {@link DetectorFactory#isEnabledFor(AsyncTestConfig)},
     * and instantiate.
     */
    public static DetectorRegistry build(AsyncTestConfig config) {
        Map<DetectorType, Detector> detectors = new EnumMap<>(DetectorType.class);
        for (DetectorFactory f : ServiceLoader.load(DetectorFactory.class)) {
            if (f.isEnabledFor(config)) {
                detectors.put(f.type(), f.create(config));
            }
        }
        return new DetectorRegistry(detectors);
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
    public List<Violation> analyzeAll() {
        List<Violation> out = new ArrayList<>();
        for (Detector d : byType.values()) {
            out.addAll(d.analyze());
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
