package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in {@code diagnostics/} may look like a detector and never be analyzed.
 *
 * <p><strong>The failure this prevents, measured rather than imagined.</strong> The package holds
 * 149 public classes whose names end in {@code Detector}, {@code Validator} or {@code Monitor}.
 * 146 are a {@link se.deversity.asynctest.DetectorType}, are built by {@code DetectorRegistry},
 * are analyzed on every run and can fail a test through {@code failOn}. The rest have the same
 * shape - a {@code record*} API and an {@code analyze()} returning a report with
 * {@code hasIssues()} - and the runner never calls {@code analyze()} on them.
 *
 * <p>Instrumenting one of those and getting a clean report is indistinguishable from instrumenting
 * a real detector and having nothing to report. {@code examples/28-lazy-init} promised "broken DCL
 * detected by LazyInitValidator" and could not deliver it under any instrumentation; it lost its
 * demonstration in #363, and the cause took a while to find precisely because the class looks like
 * every other one in the directory. See issue #374.
 *
 * <p>The existing coverage gates cannot catch this. {@code DetectorFeedCoverageTest},
 * {@code DetectorTrustCoverageTest} and {@code DetectorCatalogCoverageTest} all iterate
 * {@code DetectorType.values()}, so a class with no {@code DetectorType} is invisible to every one
 * of them: they check that each of the 146 is complete, not that nothing else is pretending to be
 * one.
 */
class DetectorShapedClassesAreReachableTest {

    /**
     * Classes that carry the naming convention and are deliberately not analyzed by the runner.
     *
     * <p>A line here is an admission, not an exemption, and it needs a reason. Adding one should
     * feel worse than wiring the class up.
     */
    private static final Map<String, String> DELIBERATELY_STANDALONE = Map.of(
            "LazyInitValidator",
            "legacy standalone helper: no DetectorType, no registry entry, driven and read by the "
                    + "caller. The wired detectors for broken DCL are DoubleCheckedLockingDetector "
                    + "and LazyInitRaceDetector.",
            "NotifyAllValidator",
            "legacy standalone helper, same shape as LazyInitValidator. The wired detector for the "
                    + "condition is NotifyWithoutMonitorDetector.");

    @Test
    @DisplayName("every detector-shaped class is analyzed by the runner, or admits that it is not")
    void nothingLooksLikeADetectorWithoutBeingOne() {
        Path root = repoRoot();
        String wiring = read(root.resolve(
                        "async-test-lib/src/main/java/se/deversity/asynctest/DetectorRegistry.java"))
                + read(root.resolve(
                        "async-test-lib/src/main/java/se/deversity/asynctest/runner/ConcurrencyRunner.java"));

        Set<String> shaped = detectorShapedClasses(root);
        assertTrue(shaped.size() > 100,
                "Expected to find the detector set; found only " + shaped.size()
                        + ", so this scan is looking in the wrong place and checking nothing.");

        List<String> unreachable = new ArrayList<>();
        for (String name : shaped) {
            if (wiring.contains("new " + name + "(") || DELIBERATELY_STANDALONE.containsKey(name)) {
                continue;
            }
            unreachable.add(name);
        }

        assertTrue(unreachable.isEmpty(),
                "These are public, live in diagnostics/, are named like every other detector, and "
                        + "nothing ever calls analyze() on them: " + unreachable
                        + ". A caller who instruments one gets a clean report that means nothing, "
                        + "which is what cost examples/28-lazy-init its demonstration. Wire the "
                        + "class into DetectorRegistry, or add it to DELIBERATELY_STANDALONE with "
                        + "a reason and say so in its javadoc.");

        List<String> stale = DELIBERATELY_STANDALONE.keySet().stream()
                .filter(name -> wiring.contains("new " + name + "(") || !shaped.contains(name))
                .sorted()
                .toList();
        assertTrue(stale.isEmpty(),
                "These are listed as deliberately standalone but are wired now, or are gone: "
                        + stale + ". Delete the line; a stale exemption hides the next real one.");
    }

    @Test
    @DisplayName("each standalone class says in its own javadoc that the runner never analyzes it")
    void theStandaloneOnesSaySoWhereTheReaderIs() {
        Path diagnostics = repoRoot().resolve(
                "async-test-lib/src/main/java/se/deversity/asynctest/diagnostics");

        List<String> silent = new ArrayList<>();
        for (String name : new TreeSet<>(DELIBERATELY_STANDALONE.keySet())) {
            String source = read(diagnostics.resolve(name + ".java"));
            int declaration = source.indexOf("public class " + name);
            String javadoc = declaration < 0 ? "" : source.substring(0, declaration);
            if (!javadoc.contains("never analyzes") && !javadoc.contains("never analyzed")) {
                silent.add(name);
            }
        }

        assertTrue(silent.isEmpty(),
                "The list in this test is not where a reader looks: they are in the class. These "
                        + "do not say the runner never analyzes them: " + silent
                        + ". Say it in the class javadoc, and name what to use instead.");
    }

    /** {@return every public class under diagnostics/ whose name carries the convention} */
    private static Set<String> detectorShapedClasses(Path root) {
        Path diagnostics = root.resolve(
                "async-test-lib/src/main/java/se/deversity/asynctest/diagnostics");
        try (Stream<Path> files = Files.list(diagnostics)) {
            Set<String> names = new TreeSet<>();
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".java", "");
                        if (!name.endsWith("Detector") && !name.endsWith("Validator")
                                && !name.endsWith("Monitor")) {
                            return;
                        }
                        String source = read(p);
                        if (source.contains("public class " + name)
                                || source.contains("public final class " + name)
                                || source.contains("public abstract class " + name)) {
                            names.add(name);
                        }
                    });
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + diagnostics, e);
        }
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
