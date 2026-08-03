package se.deversity.asynctest.spi;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
        // Built-ins are listed in META-INF/async-test/builtin-detector-factories rather than in a
        // services file, so this asks the registry that reads that list rather than ServiceLoader.
        // The guarantee is unchanged: every DetectorType must be addressable through the SPI.
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        Set<DetectorType> covered = DetectorRegistry.build(cfg).all().stream()
                .map(Detector::type)
                .collect(Collectors.toCollection(HashSet::new));

        Set<DetectorType> missing = EnumSet.allOf(DetectorType.class);
        missing.removeAll(covered);

        assertTrue(missing.isEmpty(),
                "DetectorType values without a registered DetectorFactory: " + missing
                        + ". Add an entry in LegacyDetectorFactories and in "
                        + "META-INF/async-test/builtin-detector-factories.");
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
    void everyBuiltInFactoryEntryInstantiatesCleanly() {
        // Exercising the full list confirms every entry instantiates: a typo in
        // META-INF/async-test/builtin-detector-factories is a ClassNotFoundException at build()
        // time, not a quietly smaller registry.
        //
        // Counted over the built-in bridge package only. Third-party factories are exactly what
        // the SPI is for, and one (ExternalTestDetectorFactory) is registered on the test
        // classpath; counting it here would turn "a user added a detector" into a failure of the
        // built-in-completeness check.
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        long count = DetectorRegistry.build(cfg).all().stream()
                .filter(d -> d.getClass().getName().startsWith("se.deversity.asynctest.")
                        && !d.getClass().getName().contains("ExternalTestDetector"))
                .map(Detector::type)
                .distinct()
                .count();

        assertEquals(DetectorType.values().length, count,
                "Built-in factory count must equal DetectorType.values().length; "
                        + "this catches duplicates and missing entries simultaneously.");
    }
}
