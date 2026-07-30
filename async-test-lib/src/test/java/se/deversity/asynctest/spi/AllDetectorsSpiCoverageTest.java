package se.deversity.asynctest.spi;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single source of truth for SPI coverage: every value in {@link DetectorType}
 * must be addressable through the {@link DetectorRegistry} when
 * {@code detectAll = true}. If a new enum value is added without a matching
 * {@link DetectorFactory}, this test fails with a precise list of the missing
 * types — which is the only place anyone needs to look to keep the SPI complete.
 */
class AllDetectorsSpiCoverageTest {

    @Test
    void everyDetectorTypeHasARegisteredFactory() {
        Set<DetectorType> covered = StreamSupport
                .stream(ServiceLoader.load(DetectorFactory.class).spliterator(), false)
                .map(DetectorFactory::type)
                .collect(Collectors.toCollection(HashSet::new));

        Set<DetectorType> missing = EnumSet.allOf(DetectorType.class);
        missing.removeAll(covered);

        assertTrue(missing.isEmpty(),
                "DetectorType values without a registered DetectorFactory: " + missing
                        + ". Add an entry in LegacyDetectorFactories and the META-INF/services file.");
    }

    @Test
    void buildingRegistryWithDetectAllInstantiatesEveryType() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);

        Set<DetectorType> missing = EnumSet.allOf(DetectorType.class);
        for (Detector d : reg.all()) {
            missing.remove(d.type());
        }

        assertTrue(missing.isEmpty(),
                "detectAll=true must instantiate every DetectorType; missing: " + missing);
    }

    @Test
    void factoriesUseStableServiceLoaderOrdering() {
        // ServiceLoader returns factories in the order listed in META-INF/services.
        // Even though we don't depend on a specific order at runtime, exercising
        // the full iteration confirms every entry instantiates cleanly (no
        // ClassNotFoundException from a typo in the services file).
        //
        // Counted over the built-in bridge package only: third-party factories are exactly
        // what the SPI is for, and one of them (ExternalTestDetectorFactory) is registered on
        // the test classpath. Counting those here would turn "a user added a detector" into a
        // failure of the built-in-completeness check.
        long count = StreamSupport
                .stream(ServiceLoader.load(DetectorFactory.class).spliterator(), false)
                .filter(f -> f.getClass().getName().startsWith("se.deversity.asynctest.spi.adapters."))
                .map(DetectorFactory::type)
                .count();
        assertEquals(DetectorType.values().length, count,
                "Built-in factory count must equal DetectorType.values().length; "
                        + "this catches duplicates and missing entries simultaneously.");
    }
}
