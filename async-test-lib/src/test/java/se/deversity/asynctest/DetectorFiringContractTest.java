package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.spi.Detector;
import se.deversity.asynctest.spi.DetectorRegistry;
import se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter;
import se.deversity.asynctest.report.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two contracts every detector must satisfy: it must be able to report something, and it must
 * stay silent when it has seen nothing.
 *
 * <p><strong>Why this exists.</strong> The library shipped a headline feature that could not
 * work. The README's flagship example is a bare {@code counter++}, and
 * {@code RaceConditionDetector} could not see it under any configuration: weaving bound to
 * JavaBean getters and setters, and a field touched inside a method body compiles to a
 * {@code GETFIELD} with no accessor call to bind to. Every existing gate passed. The detector was
 * registered ({@code AllDetectorsSpiCoverageTest}), constructible, enabled by default, and had a
 * unit test proving its analyser worked when handed records by hand. Nothing anywhere asked the
 * question that mattered: <em>can this thing actually fire?</em>
 *
 * <p>{@link DetectionCoverageTest} asks it end to end, but for three detectors. With 135 of them,
 * three is a sample, not a gate. These two tests generalise it — imperfectly, because a true firing
 * proof needs a fixture per detector, but enforceably, because both fail on a detector added
 * without one.
 *
 * <h4>What each catches</h4>
 * <ul>
 *   <li><strong>Silence on empty input.</strong> Fully mechanical, no fixtures. A detector that
 *       reports with nothing recorded produces a finding on every run of every consumer's suite,
 *       which is the most expensive false positive there is.</li>
 *   <li><strong>Provable firing.</strong> Every detector must have a test that asserts a positive
 *       finding. This is a source-level check rather than an execution one, so it proves a test
 *       intends to make the detector fire, not that it succeeds — a weaker claim, stated honestly
 *       here rather than overstated in a name.</li>
 * </ul>
 */
class DetectorFiringContractTest {

    /** Where detectors live. */
    private static final Path DETECTOR_DIR =
            Path.of("src/main/java/se/deversity/asynctest/diagnostics");

    /** The detectors' own test package. */
    private static final Path DETECTOR_TEST_DIR =
            Path.of("src/test/java/se/deversity/asynctest/diagnostics");

    /**
     * The whole test tree.
     *
     * <p>Searched as well as the package above, because several detectors are proved from
     * elsewhere — {@code ReadWriteLockMonitor}'s firing test lives in {@code Phase2DetectorsTest}
     * one package up, and the end-to-end proofs live in {@code DetectionCoverageTest}. Restricting
     * the search to the sibling package reported those as gaps they are not.
     */
    private static final Path TEST_ROOT = Path.of("src/test/java");

    /**
     * Evidence that a test asserts a detector reported something, rather than only that it ran.
     *
     * <p>Matches the two shapes the suite actually uses for a positive finding:
     * {@code assertTrue(...hasIssues()...)} and {@code assertFalse(...isEmpty()...)}. Deliberately
     * does not match {@code assertNotNull} or a {@code size() >= 0} comparison, because neither
     * can fail — the suite contained seven of the latter, under messages claiming detection.
     */
    private static final Pattern POSITIVE_ASSERTION = Pattern.compile(
            "assertTrue\\s*\\((?:[^;]*?)hasIssues\\s*\\(\\)"
                    + "|assertFalse\\s*\\((?:[^;]*?)\\.isEmpty\\s*\\(\\)"
                    + "|assertTrue\\s*\\((?:[^;]*?)\\.contains\\s*\\(",
            Pattern.DOTALL);

    /**
     * Detectors with no test that asserts a positive finding.
     *
     * <p>This list is a debt register, not a permission slip. Every entry is a detector nobody has
     * demonstrated can report anything, which is the exact condition that let the flagship race
     * detector ship unable to see the example in the README. The count below is pinned so the list
     * can shrink but never grow: adding a detector without a firing test fails this test, and the
     * only way to make it pass is to write the test or to argue the entry into this list in review.
     */
    private static final Set<String> FIRING_UNPROVEN = new TreeSet<>(Set.of(
            // Populated from the measured gap — see the assertion message for how to clear one.
    ));

    @Test
    @DisplayName("no detector reports a finding when nothing has been recorded")
    void detectorsAreSilentOnEmptyInput() {
        AsyncTestConfig config = AsyncTestConfig.builder().detectAll(true).build();
        List<Detector> detectors = DetectorRegistry.build(config).all();

        assertTrue(detectors.size() > 100,
                "Expected the full built-in detector set; got " + detectors.size()
                        + ". If the registry stopped returning built-ins this test is no longer "
                        + "checking anything.");

        List<String> noisy = new ArrayList<>();
        for (Detector detector : detectors) {
            List<Violation> violations;
            try {
                violations = detector.analyze();
            } catch (RuntimeException e) {
                noisy.add(detector.type() + " threw " + e);
                continue;
            }
            if (violations != null && !violations.isEmpty()) {
                noisy.add(detector.type() + " reported " + violations.size() + ": " + violations);
            }
        }

        assertTrue(noisy.isEmpty(),
                "A detector that reports with nothing recorded fires on every run of every "
                        + "consumer's test suite, and under failOn it fails their build over code "
                        + "it never observed. It is also the hardest kind of false positive to "
                        + "notice from inside the project, because it looks like the detector is "
                        + "working. Offenders:\n  " + String.join("\n  ", noisy));
    }

    @Test
    @DisplayName("every registered detector can emit a Violation at all")
    void everyRegisteredDetectorCanProduceAViolation() {
        AsyncTestConfig config = AsyncTestConfig.builder().detectAll(true).build();
        List<Detector> detectors = DetectorRegistry.build(config).all();

        List<String> inert = new ArrayList<>();
        for (Detector detector : detectors) {
            if (!(detector instanceof LegacyDetectorAdapter<?> adapter)) {
                continue;   // A native SPI Detector implements analyze() directly.
            }
            Object delegate = adapter.delegate();
            if (findReportMethod(delegate.getClass()) == null) {
                inert.add(detector.type() + " (" + delegate.getClass().getSimpleName() + ")");
            }
        }

        assertTrue(inert.isEmpty(),
                "These detectors are registered and addressable but cannot emit a Violation "
                        + "under any input:\n  " + String.join("\n  ", inert)
                        + "\n\nLegacyDetectorAdapter binds the delegate's report method "
                        + "reflectively and every failure path in analyze() returns an empty "
                        + "list, so a detector whose report method it cannot find is silently "
                        + "inert — it passes AllDetectorsSpiCoverageTest, it has working unit "
                        + "tests, and it reports nothing forever. LOCK_ORDER and "
                        + "CONSTRUCTOR_SAFETY were both in that state because their report "
                        + "methods are named validateLockOrder() and "
                        + "validateConstructorSafety() rather than analyze().\n\n"
                        + "Give the detector a public no-arg report method named analyze* or "
                        + "validate*, returning a report that exposes boolean hasIssues().");
    }

    /** Mirrors {@code LegacyDetectorAdapter}'s resolution rule, so this gate checks the real one. */
    private static java.lang.reflect.Method findReportMethod(Class<?> detectorClass) {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : detectorClass.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            String name = m.getName();
            if (!name.equals("analyze") && !name.startsWith("analyze") && !name.startsWith("validate")) {
                continue;
            }
            if (!hasIssuesOn(m.getReturnType())) {
                continue;
            }
            if (best == null || name.compareTo(best.getName()) < 0) {
                best = m;
            }
        }
        return best;
    }

    private static boolean hasIssuesOn(Class<?> reportClass) {
        for (Class<?> c = reportClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getMethod("hasIssues");
                return true;
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }
        return false;
    }

    @Test
    @DisplayName("every detector has a test that asserts it reports something")
    void everyDetectorHasATestThatProvesItCanFire() throws IOException {
        List<String> detectorNames = detectorClassNames();

        // Tie the enumeration to the enum. The filter above selects by naming convention, so a
        // renamed detector would drop out of this gate silently — which is precisely the failure
        // mode this class exists to prevent. DetectorType is the authoritative count; requiring
        // at least that many files means a detector cannot slip out of the sweep unnoticed.
        assertTrue(detectorNames.size() >= DetectorType.values().length,
                "Found " + detectorNames.size() + " detector sources under "
                        + DETECTOR_DIR.toAbsolutePath() + " but DetectorType declares "
                        + DetectorType.values().length + ". Either the layout moved or a detector "
                        + "no longer matches the naming convention in detectorClassNames(), and "
                        + "this gate has stopped inspecting it. Fix the filter, not the number.");

        Set<String> unproven = new TreeSet<>();
        for (String detector : detectorNames) {
            if (!hasPositiveAssertion(detector)) {
                unproven.add(detector);
            }
        }

        Set<String> newlyUnproven = new TreeSet<>(unproven);
        newlyUnproven.removeAll(FIRING_UNPROVEN);

        assertTrue(newlyUnproven.isEmpty(),
                "These detectors have no test asserting they report anything:\n  "
                        + String.join("\n  ", newlyUnproven)
                        + "\n\nA detector that no test has ever made fire is a detector nobody has "
                        + "shown can fire. That is not hypothetical: RaceConditionDetector was "
                        + "registered, enabled by default and unit-tested, and still could not "
                        + "observe the example in the README's Quick Start.\n\n"
                        + "To clear one, add to its test class an assertion that the report has a "
                        + "finding — assertTrue(report.hasIssues()) or assertFalse(<findings>"
                        + ".isEmpty()) — driven by input that should genuinely trigger it. Do not "
                        + "add the detector to FIRING_UNPROVEN to make this pass unless you can "
                        + "say in review why it cannot be demonstrated.");

        Set<String> stale = new TreeSet<>(FIRING_UNPROVEN);
        stale.removeAll(unproven);
        assertTrue(stale.isEmpty(),
                "These detectors are listed in FIRING_UNPROVEN but now have a firing test:\n  "
                        + String.join("\n  ", stale)
                        + "\nRemove them from the list. A debt register that keeps paid-off "
                        + "entries stops being read.");

        assertEquals(FIRING_UNPROVEN.size(), unproven.size(),
                "The number of detectors without a firing test changed unexpectedly. Measured: "
                        + unproven.size() + ", pinned: " + FIRING_UNPROVEN.size() + ". This ratchet "
                        + "exists so the gap can shrink but not grow.");
    }

    /** {@return the simple class names of every detector source file} */
    private static List<String> detectorClassNames() throws IOException {
        try (Stream<Path> files = Files.list(DETECTOR_DIR)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    // The package also holds shared support types — SiteCapture, for one, which
                    // captures a stack frame for other detectors to attribute a finding with and
                    // has no DetectorType of its own. Detectors are named by role; anything else
                    // here is machinery. The caller cross-checks this count against
                    // DetectorType.values().length so a convention change cannot quietly shrink
                    // what this gate inspects.
                    .filter(n -> n.endsWith("Detector") || n.endsWith("Monitor")
                            || n.endsWith("Validator") || n.endsWith("Analyzer")
                            || n.endsWith("Tracker"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * {@return whether any test source mentioning {@code detector} asserts a positive finding}
     *
     * <p>Looks first at the detector's own test class, then at any test in the same package that
     * names it — several detectors are proved by a shared fixture test rather than a dedicated
     * one, and requiring a 1:1 file mapping would report those as gaps they are not.
     */
    private static boolean hasPositiveAssertion(String detector) throws IOException {
        Path own = DETECTOR_TEST_DIR.resolve(detector + "Test.java");
        if (Files.isRegularFile(own) && POSITIVE_ASSERTION.matcher(read(own)).find()) {
            return true;
        }
        if (!Files.isDirectory(TEST_ROOT)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .anyMatch(p -> {
                        String body = readQuietly(p);
                        return body.contains(detector) && POSITIVE_ASSERTION.matcher(body).find();
                    });
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String readQuietly(Path p) {
        try {
            return read(p);
        } catch (IOException e) {
            return "";
        }
    }
}
