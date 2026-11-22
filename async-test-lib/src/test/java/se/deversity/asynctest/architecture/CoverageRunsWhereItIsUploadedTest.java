package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests & Build runs JaCoCo on the one matrix leg that uploads coverage, and skips it on the
 * others (#899).
 *
 * <p>Only {@code ubuntu-latest} with JDK 21 uploads to Codecov, yet every leg ran the suite under
 * the JaCoCo agent, which roughly doubles serial test time in this repo's own measurement. One flag
 * decides both, so the coverage steps and the skip cannot drift apart: a skipped leg that still ran
 * "Check coverage file exists" would fail on the file it no longer writes, and an uploading leg that
 * skipped JaCoCo would upload nothing. The {@code jacoco-check} gate still runs on the uploading leg.
 */
class CoverageRunsWhereItIsUploadedTest {

    private static final String WORKFLOW = ".github/workflows/tests.yml";

    /** The one leg whose coverage anyone reads. */
    private static final String UPLOADS_COVERAGE =
            "UPLOADS_COVERAGE: ${{ matrix.os == 'ubuntu-latest' && matrix.java-version == '21' }}";

    /** The guard every coverage step carries. */
    private static final String ON_THE_UPLOADING_LEG = "if: env.UPLOADS_COVERAGE == 'true'";

    @Test
    @DisplayName("the test job decides once which leg uploads coverage")
    void oneFlagDecidesTheUploadingLeg() {
        assertTrue(read().contains(UPLOADS_COVERAGE),
                WORKFLOW + " must define '" + UPLOADS_COVERAGE + "' on the test job, so the skip "
                        + "and the coverage steps read the same answer (#899)");
    }

    @Test
    @DisplayName("every other leg runs the suite with JaCoCo skipped")
    void theOtherLegsSkipJacoco() {
        String run = stepBody("Run library tests and install artifact");
        // Quoted: the Windows legs run this step in PowerShell, which splits a bare
        // -Djacoco.skip=true at the dot and hands Maven ".skip=true" as a lifecycle phase. The
        // first dispatch of #899 failed every Windows leg that way in seconds.
        assertTrue(run.contains("mvn clean install ${{ env.UPLOADS_COVERAGE != 'true' && '\"-Djacoco.skip=true\"' || '' }}"),
                "the build step must pass \"-Djacoco.skip=true\", quoted so PowerShell keeps it "
                        + "whole, on every leg that does not upload coverage (#899): " + run);
    }

    @Test
    @DisplayName("the coverage steps run only where JaCoCo ran")
    void coverageStepsFollowTheFlag() {
        List<String> guarded = new ArrayList<>();
        for (String step : List.of("Check coverage file exists", "Import Codecov GPG key",
                "Upload coverage to Codecov")) {
            if (stepBody(step).contains(ON_THE_UPLOADING_LEG)) {
                guarded.add(step);
            }
        }
        assertEquals(3, guarded.size(), "each coverage step must carry '" + ON_THE_UPLOADING_LEG
                + "', or a leg without JaCoCo fails looking for the report; guarded: " + guarded);
    }

    @Test
    @DisplayName("a hung coverage step cannot hold the required check for six hours")
    void theCoverageNetworkStepsAreBounded() {
        for (String step : List.of("Import Codecov GPG key", "Upload coverage to Codecov")) {
            String body = stepBody(step);
            assertTrue(body.contains("timeout-minutes:"),
                    "'" + step + "' needs a timeout-minutes: the codecov wrapper hung twice in its "
                            + "signature check, and without one a step waits out the job's 360 minutes "
                            + "with the Test Suite check pending (#1372): " + body);
            assertTrue(body.contains("continue-on-error: true"),
                    "'" + step + "' needs continue-on-error: true: a timed-out step fails its job, "
                            + "and coverage is not what the check is for (#1372): " + body);
        }
    }

    /** The phases that run before {@code package}, where the agent's shade step moves its basedir. */
    private static final List<String> BEFORE_PACKAGE = List.of("validate", "initialize", "generate-sources",
            "process-sources", "generate-resources", "process-resources", "compile", "process-classes",
            "generate-test-sources", "process-test-sources", "generate-test-resources",
            "process-test-resources", "test-compile", "process-test-classes", "test", "prepare-package");

    @Test
    @DisplayName("#1482: the uploaded report is the library-and-agent aggregate, built before shade")
    void theAggregateIsUploadedAndBuiltBeforeShadeMovesTheBasedir() {
        assertTrue(stepBody("Upload coverage to Codecov")
                        .contains("./async-test-agent/target/site/jacoco-aggregate/jacoco.xml"),
                "Codecov must receive the aggregate report: the library's own report leaves out the "
                        + "coverage the agent's tests give the library's agent hooks (#1482)");
        String pom = readFile("async-test-agent/pom.xml");
        int execution = pom.indexOf("<id>report-aggregate</id>");
        assertTrue(execution >= 0, "async-test-agent/pom.xml must run JaCoCo's report-aggregate (#1482)");
        int phaseStart = pom.indexOf("<phase>", execution) + "<phase>".length();
        String phase = pom.substring(phaseStart, pom.indexOf("</phase>", phaseStart)).trim();
        assertTrue(BEFORE_PACKAGE.contains(phase),
                "report-aggregate is bound to '" + phase + "', but it must run before package: shade "
                        + "writes its dependency-reduced pom to target/, which moves the basedir, and an "
                        + "aggregate after it reads the library's data alone and reports the agent at 0%");
    }

    /** {@return the lines of the step named {@code name}, up to the next step} */
    private static String stepBody(String name) {
        String workflow = read();
        int start = workflow.indexOf("- name: " + name);
        assertTrue(start >= 0, "no step named '" + name + "' in " + WORKFLOW);
        int end = workflow.indexOf("\n      - name: ", start + 1);
        return workflow.substring(start, end < 0 ? workflow.length() : end);
    }

    private static String read() {
        return readFile(WORKFLOW);
    }

    private static String readFile(String relative) {
        Path path = repoRoot().resolve(relative);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /** Walks up from the working directory to the reactor root, as Maven and Gradle both start below it. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
