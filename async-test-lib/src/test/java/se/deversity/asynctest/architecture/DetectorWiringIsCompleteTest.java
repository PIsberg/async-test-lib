package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every detector switch on {@code @AsyncTest} reaches the config, and there is one per detector.
 *
 * <p><strong>The failure this prevents.</strong> Turning a detector on travels through three
 * places that nothing tied together: the attribute on {@link AsyncTest}, the
 * {@code ann.detectXxx()} read in {@code AsyncTestConfig.from}, and the resolution line in
 * {@code AsyncTestConfig.Builder.build}. {@code AsyncTestConfigBuildResolutionTest} pins the third
 * by counting fields reflectively. Nothing pinned the second, so an attribute that was declared and
 * never read would compile, ship, and silently ignore the user who set it: they would switch a
 * detector on, get a clean report, and have no way to tell that from a detector that found nothing.
 * That is the shape of failure this repository has paid for repeatedly.
 *
 * <p>It has not happened yet - all 150 boolean attributes are read today - which is the right time
 * to pin it rather than the wrong one.
 *
 * <p><strong>And why this matters beyond the bug.</strong> The count assertion below states the
 * correspondence the 2.0 refactor has to mechanize: one {@link DetectorType}, one annotation
 * attribute, no strays on either side. {@code docs/analysis/roadmap-v2.md} plans to replace the
 * hand-written resolution with an {@code EnumSet} computed from a table; that is only safe if the
 * two sets are known to match, and until now nothing said they did.
 */
class DetectorWiringIsCompleteTest {

    /**
     * Boolean attributes on {@code @AsyncTest} that are not detector switches.
     *
     * <p>A line here needs a reason. Each of these configures the run rather than enabling a
     * detector, which is why none has a resolution line in {@code build()}.
     */
    private static final Map<String, String> NOT_A_DETECTOR_SWITCH = Map.of(
            "detectAll", "the umbrella toggle every detector switch is resolved against",
            "useVirtualThreads", "picks the executor the workers run on",
            "enableBenchmarking", "turns on throughput recording, which reports rather than detects",
            "failOnBenchmarkRegression", "a gate on the benchmark, not a detector",
            "licenseMockMode", "bypasses the licence check for local runs");

    @Test
    @DisplayName("every detector switch on @AsyncTest is read by from() and resolved in build()")
    void everySwitchReachesTheConfig() {
        String config = read(repoRoot().resolve(
                "async-test-lib/src/main/java/se/deversity/asynctest/AsyncTestConfig.java"));

        List<String> unread = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String attribute : detectorSwitches()) {
            if (!config.contains("." + attribute + "()")) {
                unread.add(attribute);
            }
            if (!config.contains(attribute + " = (detectAll || " + attribute + ")")) {
                unresolved.add(attribute);
            }
        }

        assertTrue(unread.isEmpty(),
                "These are declared on @AsyncTest and never read in AsyncTestConfig: " + unread
                        + ". Setting one would compile and do nothing: the user switches a "
                        + "detector on, gets a clean report, and cannot tell that from a detector "
                        + "that found nothing. Read it in from().");
        assertTrue(unresolved.isEmpty(),
                "These have no resolution line in build(): " + unresolved
                        + ". Without one the flag is read and then never reconciled against "
                        + "detectAll and excludes, so excludes cannot switch it off. Add "
                        + "`x = (detectAll || x) && !excludes.contains(TYPE);`.");
    }

    @Test
    @DisplayName("one detector switch per DetectorType, and no strays on either side")
    void theSwitchesAndTheEnumAgree() {
        int switches = detectorSwitches().size();
        int types = DetectorType.values().length;

        assertEquals(types, switches,
                "There are " + types + " DetectorType constants and " + switches + " detector "
                        + "switches on @AsyncTest. They have to match one-for-one: a constant with "
                        + "no attribute cannot be turned on from the annotation, and an attribute "
                        + "with no constant cannot be excluded or named in a preset. This "
                        + "correspondence is also what docs/analysis/roadmap-v2.md's EnumSet "
                        + "refactor rests on, so it is pinned before that work rather than after. "
                        + "If a new attribute is deliberately not a detector, add it to "
                        + "NOT_A_DETECTOR_SWITCH with a reason.");
    }

    /** {@return every boolean attribute on {@code @AsyncTest} that switches a detector on} */
    private static Set<String> detectorSwitches() {
        Set<String> names = new TreeSet<>();
        for (Method m : AsyncTest.class.getDeclaredMethods()) {
            if (m.getReturnType() != boolean.class || m.getParameterCount() != 0) {
                continue;
            }
            if (NOT_A_DETECTOR_SWITCH.containsKey(m.getName())) {
                continue;
            }
            names.add(m.getName());
        }
        return names;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
