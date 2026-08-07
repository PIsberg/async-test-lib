package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completeness gate for this package.
 *
 * <p>Reflects over every fixture class listed in {@link #FIXTURES}, collects the
 * {@link DetectorType}s their {@code @AsyncTest(includes = {...})} methods enable, and fails
 * unless the union is exactly {@code DetectorType.values()} — one fixture per detector, no
 * detector covered twice, no fixture pointing at a detector that no longer exists.
 *
 * <p>This is what keeps the fixture honest as the library grows. Adding a
 * {@code DetectorType} constant without a consumer fixture fails here rather than quietly
 * shipping a detector no downstream test ever reaches.
 *
 * <p>Fixture classes are listed explicitly rather than discovered by classpath scanning: the
 * consumer fixture depends only on the published artifact and JUnit, and an explicit list is
 * itself a readable index of the package.
 */
class DetectorCoverageTest {

    /** Every per-detector fixture class, in {@link DetectorType} declaration order. */
    static final List<Class<?>> FIXTURES = List.of(
        Phase01FoundationDetectorsFixtureTest.class,
        Phase02CoreDetectorsFixtureTest.class,
        Phase02MonitorDetectorsFixtureTest.class,
        Phase02AdditionalConcurrencyDetectorsFixtureTest.class,
        Phase02AdvancedUtilityDetectorsFixtureTest.class,
        Phase03RuntimeAnalysisDetectorsFixtureTest.class,
        Phase04InfrastructureDetectorsFixtureTest.class,
        Phase05CommonTypeDetectorsFixtureTest.class,
        Phase06VirtualThreadDetectorsFixtureTest.class,
        Phase07HighLevelPatternDetectorsFixtureTest.class,
        Phase08LifecycleDetectorsFixtureTest.class,
        Phase10ApiTrapDetectorsFixtureTest.class,
        Phase11SharedTypeDetectorsFixtureTest.class,
        Phase12OperationalHygieneDetectorsFixtureTest.class,
        Phase13AdditionalCategoryDetectorsFixtureTest.class,
        Phase14PublicationHazardDetectorsFixtureTest.class,
        Phase15AsyncFlowDetectorsFixtureTest.class,
        Phase16PreviewEraDetectorsFixtureTest.class,
        Phase17SharedStatefulJdkDetectorsFixtureTest.class,
        Phase18GaEraDetectorsFixtureTest.class,
        UnwiredExecutorDetectorsFixtureTest.class,
        Phase19ReactiveStreamsDetectorsFixtureTest.class);

    /** detector -> the fixture methods that enable it, as {@code Class#method}. */
    private static Map<DetectorType, List<String>> collectCoverage() {
        Map<DetectorType, List<String>> byType = new EnumMap<>(DetectorType.class);
        for (Class<?> fixture : FIXTURES) {
            for (Method m : fixture.getDeclaredMethods()) {
                AsyncTest ann = m.getAnnotation(AsyncTest.class);
                if (ann == null) {
                    continue;
                }
                for (DetectorType type : ann.includes()) {
                    byType.computeIfAbsent(type, t -> new ArrayList<>())
                        .add(fixture.getSimpleName() + "#" + m.getName());
                }
            }
        }
        return byType;
    }

    @Test
    void every_detector_type_has_a_consumer_fixture() {
        Set<DetectorType> covered = EnumSet.noneOf(DetectorType.class);
        covered.addAll(collectCoverage().keySet());

        Set<DetectorType> missing = EnumSet.allOf(DetectorType.class);
        missing.removeAll(covered);

        assertTrue(missing.isEmpty(),
            "Every DetectorType must have a consumer fixture in "
                + DetectorCoverageTest.class.getPackageName()
                + ". Missing: " + missing);
    }

    @Test
    void no_detector_type_is_covered_twice() {
        List<String> duplicated = new ArrayList<>();
        collectCoverage().forEach((type, methods) -> {
            if (methods.size() > 1) {
                duplicated.add(type + " -> " + methods);
            }
        });

        assertTrue(duplicated.isEmpty(),
            "Each DetectorType must be covered by exactly one fixture method so a failure "
                + "names one detector. Duplicated: " + duplicated);
    }

    @Test
    void fixture_method_count_matches_detector_count() {
        int fixtureMethods = 0;
        for (Class<?> fixture : FIXTURES) {
            for (Method m : fixture.getDeclaredMethods()) {
                if (m.isAnnotationPresent(AsyncTest.class)) {
                    fixtureMethods++;
                }
            }
        }

        assertEquals(DetectorType.values().length, fixtureMethods,
            "One @AsyncTest fixture method per DetectorType — a mismatch means a fixture "
                + "enables several detectors at once, which blurs which one a failure belongs to");
    }

    @Test
    void every_fixture_method_enables_exactly_one_detector() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> fixture : FIXTURES) {
            for (Method m : fixture.getDeclaredMethods()) {
                AsyncTest ann = m.getAnnotation(AsyncTest.class);
                if (ann != null && ann.includes().length != 1) {
                    offenders.add(fixture.getSimpleName() + "#" + m.getName()
                        + " includes " + ann.includes().length + " detectors");
                }
            }
        }

        assertTrue(offenders.isEmpty(),
            "Per-detector fixtures must scope to a single detector via includes = {ONE}: "
                + offenders);
    }
}
