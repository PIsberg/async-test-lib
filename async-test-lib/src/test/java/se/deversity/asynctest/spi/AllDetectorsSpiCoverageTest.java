package se.deversity.asynctest.spi;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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

    /**
     * Every built-in detector's report can be read through {@link LegacyDetectorAdapter} (#847).
     *
     * <p>The adapter sits in another package than the detectors and calls their report method and
     * the report's {@code hasIssues()} reflectively, without {@code setAccessible}. A report method
     * or predicate declared on a class that is not public is refused with an
     * {@code IllegalAccessException}, and a detector in that state reports nothing on the SPI path
     * whatever it records. This test sits outside the detectors' package too, so each of its calls
     * meets the check the adapter's meets. It reads every candidate report method, not only the one
     * the adapter picks, so it does not depend on the adapter's choice between them.
     */
    @Test
    void everyBuiltInReportIsReachableThroughTheAdapter() throws ReflectiveOperationException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        List<String> unreachable = new ArrayList<>();
        List<String> withoutReport = new ArrayList<>();
        int adapters = 0;
        for (Detector d : DetectorRegistry.build(cfg).all()) {
            if (!(d instanceof LegacyDetectorAdapter<?> adapter)) {
                continue;   // A native SPI Detector implements analyze() itself.
            }
            adapters++;
            Object delegate = adapter.delegate();
            int reports = 0;
            for (Method m : delegate.getClass().getMethods()) {
                Method hasIssues = reportPredicate(m);
                if (hasIssues == null) {
                    continue;
                }
                String where = d.type() + " " + delegate.getClass().getSimpleName() + "." + m.getName() + "()";
                try {
                    Object report = m.invoke(delegate);
                    hasIssues.invoke(report);
                    String.valueOf(report);
                    Field structured = structuredField(report);
                    if (structured != null && !(structured.canAccess(report) || structured.trySetAccessible())) {
                        unreachable.add(where + ": " + DetectorDefaultSeverity.STRUCTURED_FIELD + " cannot be read");
                    }
                    reports++;
                } catch (IllegalAccessException e) {
                    unreachable.add(where + ": " + e.getMessage());
                } catch (InvocationTargetException e) {
                    throw new AssertionError(where + " threw on a detector that recorded nothing", e.getCause());
                }
            }
            if (reports == 0) {
                withoutReport.add(d.type() + " " + delegate.getClass().getSimpleName());
            }
        }

        assertTrue(adapters >= DetectorType.values().length - 1,
                "Expected every built-in behind the adapter; got " + adapters + ", so this test stopped checking them.");
        assertTrue(withoutReport.isEmpty(),
                "These built-ins have no report method the adapter can bind:\n  " + String.join("\n  ", withoutReport));
        assertTrue(unreachable.isEmpty(),
                "The SPI path cannot read these built-in reports, so they report nothing through "
                        + "spi.DetectorRegistry.build whatever they record. Make the report type and its "
                        + "hasIssues() public:\n  " + String.join("\n  ", unreachable));
    }

    /** {@return {@code m}'s report's {@code boolean hasIssues()}, or null when {@code m} is not a report method} */
    private static Method reportPredicate(Method m) {
        String name = m.getName();
        if (m.getParameterCount() != 0 || !(name.startsWith("analyze") || name.startsWith("validate"))) {
            return null;
        }
        try {
            Method hasIssues = m.getReturnType().getMethod("hasIssues");
            return hasIssues.getReturnType() == boolean.class ? hasIssues : null;
        } catch (NoSuchMethodException notAReport) {
            return null;
        }
    }

    /** {@return the report's public structured list, or null when its type keeps none} */
    private static Field structuredField(Object report) {
        try {
            return report.getClass().getField(DetectorDefaultSeverity.STRUCTURED_FIELD);
        } catch (NoSuchFieldException textOnly) {
            return null;
        }
    }
}
