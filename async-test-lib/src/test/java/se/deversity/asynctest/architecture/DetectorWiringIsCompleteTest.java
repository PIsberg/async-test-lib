package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detector is selected through {@code DetectorType}, and only through it.
 *
 * <p><strong>What this replaced.</strong> Until 2.0.0 {@code @AsyncTest} carried one boolean
 * attribute per detector, 146 of them, each read in {@code AsyncTestConfig.from} and resolved in
 * {@code build()}. Every new detector cost three more edit sites, and the attributes were a trap:
 * almost all defaulted to {@code true}, so {@code @AsyncTest(detectAll = false, detectX = true)},
 * which this repository's own fixtures described as "only X", enabled about 144 detectors. 2.0.0
 * removed them (#920); {@code includes}, {@code excludes} and {@code preset} say the same thing in
 * one vocabulary. This test keeps a per-detector switch from coming back, and keeps every public
 * detector flag on {@link AsyncTestConfig} derived from the one enabled set.
 */
class DetectorWiringIsCompleteTest {

    /**
     * Boolean attributes on {@code @AsyncTest} that are not detector switches.
     *
     * <p>A line here needs a reason. Each of these configures the run rather than enabling a
     * detector.
     */
    private static final Map<String, String> NOT_A_DETECTOR_SWITCH = Map.of(
            "detectAll", "the explicit opt-in to every detector",
            "useVirtualThreads", "picks the executor the workers run on",
            "enableBenchmarking", "turns on throughput recording, which reports rather than detects",
            "failOnBenchmarkRegression", "a gate on the benchmark, not a detector",
            "licenseMockMode", "bypasses the licence check for local runs");

    @Test
    @DisplayName("no per-detector boolean switch remains on @AsyncTest")
    void noPerDetectorSwitchRemains() {
        Set<String> switches = new TreeSet<>();
        for (Method m : AsyncTest.class.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && m.getParameterCount() == 0
                    && !NOT_A_DETECTOR_SWITCH.containsKey(m.getName())) {
                switches.add(m.getName());
            }
        }
        assertTrue(switches.isEmpty(),
                "These boolean attributes switch a single detector: " + switches + ". Detectors are "
                        + "selected with includes/excludes/preset over DetectorType; a per-detector "
                        + "attribute is three more edit sites per detector and was removed in 2.0.0 "
                        + "(#920). If a new attribute is deliberately not a detector, add it to "
                        + "NOT_A_DETECTOR_SWITCH with a reason.");
    }

    @Test
    @DisplayName("every public detector flag on AsyncTestConfig is derived from the enabled set")
    void everyFlagIsDerivedFromTheEnabledSet() {
        String config = read(repoRoot().resolve(
                "async-test-lib/src/main/java/se/deversity/asynctest/AsyncTestConfig.java"));
        List<String> underived = new ArrayList<>();
        int flags = 0;
        for (Field f : AsyncTestConfig.class.getFields()) {
            String n = f.getName();
            if (f.getType() != boolean.class || NOT_A_DETECTOR_SWITCH.containsKey(n)
                    || !(n.startsWith("detect") || n.startsWith("validate") || n.startsWith("monitor"))) {
                continue;
            }
            flags++;
            if (!Pattern.compile("\\b" + n + "\\s+= enabled\\.contains\\(DetectorType\\.\\w+\\);")
                    .matcher(config).find()) {
                underived.add(n);
            }
        }
        assertTrue(flags > 100, "expected the public detector flags; found " + flags);
        assertTrue(underived.isEmpty(),
                "These public flags are not derived from the enabled set: " + underived
                        + ". Without the derivation the flag is never reconciled against "
                        + "detectAll, includes and excludes. Assign "
                        + "`x = enabled.contains(DetectorType.TYPE);` in the constructor.");
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
