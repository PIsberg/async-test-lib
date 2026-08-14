package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fixture file in this package must eventually assert that its detectors reported, not
 * merely that they were reachable.
 *
 * <p><strong>Why this exists.</strong> Each file here enables one {@code DetectorType} per
 * fixture and runs a realistic workload for it, which reads like end-to-end detection coverage.
 * It was not. The load-bearing assertion was {@code reachable(...)}, which proves the accessor
 * resolves on the published artifact - genuinely useful, and silent about whether the detector
 * can still detect. Measured before this gate: of 23 fixture files, 18 called no {@code record*}
 * method at all, so ~100 fixtures ran their hazard past a detector that observed nothing, and
 * exactly one test in the whole module asserted a finding. Every one of them would have passed
 * with its detector deleted.
 *
 * <p>The fix per file is small - record the access a consumer would record, then call
 * {@code assertAllReported} from {@code @AfterAll} - but it needs each detector's firing
 * criteria understood one at a time, so it lands file by file. This gate holds the ratchet:
 * files that have been converted cannot regress, and a new fixture file cannot be added without
 * either asserting detection or arguing its way onto the debt list in review.
 *
 * <p>The count is pinned rather than the list alone, so removing a file from
 * {@link #DETECTION_UNPROVEN} without converting it fails too.
 */
@DisplayName("Fixture files must assert detection, not only reachability")
class FixtureDetectionContractTest {

    /** This package, relative to the module root - surefire runs with that as the cwd. */
    private static final Path FIXTURE_DIR =
            Path.of("src/test/java/se/deversity/asynctest/fixture/detectors");

    /**
     * Fixture files that still assert reachability only.
     *
     * <p>A debt register, not a permission slip. Each entry is a file whose detectors nobody has
     * shown can still report to a consumer. Shrink it by recording the access and calling
     * {@code assertAllReported}; the assertion message on failure names what the class did
     * report, which is usually enough to find the missing recording.
     */
    private static final Set<String> DETECTION_UNPROVEN = new TreeSet<>(Set.of(
            "Phase01FoundationDetectorsFixtureTest",
            "Phase02AdditionalConcurrencyDetectorsFixtureTest",
            "Phase02AdvancedUtilityDetectorsFixtureTest",
            "Phase02CoreDetectorsFixtureTest",
            "Phase02MonitorDetectorsFixtureTest",
            "Phase03RuntimeAnalysisDetectorsFixtureTest",
            "Phase04InfrastructureDetectorsFixtureTest",
            "Phase06VirtualThreadDetectorsFixtureTest",
            "Phase07HighLevelPatternDetectorsFixtureTest",
            "Phase08LifecycleDetectorsFixtureTest",
            "Phase10ApiTrapDetectorsFixtureTest",
            "Phase12OperationalHygieneDetectorsFixtureTest",
            "Phase13AdditionalCategoryDetectorsFixtureTest",
            "Phase14PublicationHazardDetectorsFixtureTest",
            "Phase15AsyncFlowDetectorsFixtureTest",
            "Phase16PreviewEraDetectorsFixtureTest",
            "Phase18GaEraDetectorsFixtureTest",
            "Phase19ReactiveStreamsDetectorsFixtureTest",
            "Phase20FfmAndLanguageHazardDetectorsFixtureTest",
            "Phase21VirtualThreadExecutorAndGeneratorFixtureTest",
            "UnwiredExecutorDetectorsFixtureTest"));

    @Test
    @DisplayName("no fixture file drops back to asserting reachability only")
    void everyConvertedFixtureFileStillAssertsDetection() throws IOException {
        assertTrue(Files.isDirectory(FIXTURE_DIR),
                "Fixture package not found at " + FIXTURE_DIR.toAbsolutePath()
                        + ". The layout moved and this gate is inspecting nothing.");

        Set<String> unproven = new TreeSet<>();
        Set<String> all = new TreeSet<>();
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith("FixtureTest.java")) {
                    continue;   // support types and this gate itself
                }
                String simple = name.substring(0, name.length() - ".java".length());
                all.add(simple);
                if (!Files.readString(file, StandardCharsets.UTF_8).contains("assertAllReported")) {
                    unproven.add(simple);
                }
            }
        }

        assertTrue(all.size() >= 20,
                "Found only " + all.size() + " fixture files. This package holds one per detector "
                        + "phase, so the filter has stopped matching and the gate is inspecting "
                        + "nothing. Fix the filter, not the number.");

        Set<String> regressed = new TreeSet<>(unproven);
        regressed.removeAll(DETECTION_UNPROVEN);
        assertTrue(regressed.isEmpty(),
                "These fixture files used to assert that their detectors reported, and no longer "
                        + "do:\n  " + String.join("\n  ", regressed)
                        + "\n\nA fixture that asserts only reachability cannot fail if the "
                        + "detector stops detecting. Restore the assertAllReported(...) call in "
                        + "@AfterAll.");

        Set<String> stale = new TreeSet<>(DETECTION_UNPROVEN);
        stale.removeAll(unproven);
        assertTrue(stale.isEmpty(),
                "These files are listed as unproven but now assert detection:\n  "
                        + String.join("\n  ", stale)
                        + "\nRemove them from DETECTION_UNPROVEN. A debt register that keeps "
                        + "paid-off entries stops being read.");

        assertEquals(DETECTION_UNPROVEN.size(), unproven.size(),
                "The number of fixture files that assert reachability only changed unexpectedly. "
                        + "Measured: " + unproven.size() + ", pinned: " + DETECTION_UNPROVEN.size()
                        + ". This ratchet exists so the gap can shrink but not grow.");
    }
}
