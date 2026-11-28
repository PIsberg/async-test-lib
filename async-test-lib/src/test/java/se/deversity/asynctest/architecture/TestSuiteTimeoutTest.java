package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Test Suite job, and the step that runs the library's tests, stop a hung test long before
 * GitHub's default of 360 minutes (#1516).
 *
 * <p>Measured over the last twelve green runs of tests.yml (2026-11-28): the test step took 11 to
 * 21 minutes on Ubuntu and up to 30 on the Windows and macOS legs; a starved Ubuntu runner on PR
 * #1515 took 59 minutes and passed. The bounds sit above that run so a slow runner still passes,
 * and far below six hours so a hang costs one runner-hour and shows its log.
 */
class TestSuiteTimeoutTest {

    private static final String WORKFLOW = ".github/workflows/tests.yml";

    /** The slowest correct run seen, rounded up: a bound below it would fail a passing build. */
    private static final int SLOWEST_CORRECT_MINUTES = 60;

    /** GitHub's own default, which is what having no bound means. */
    private static final int GITHUB_DEFAULT_MINUTES = 360;

    private static final Pattern TIMEOUT = Pattern.compile("(?m)^\\s*timeout-minutes:\\s*(\\d+)\\s*$");

    @Test
    @DisplayName("the library test step has a timeout between the slowest correct run and GitHub's default")
    void theTestStepIsBounded() {
        assertBounded(stepBody("Run library tests and install artifact"), "the test step");
    }

    @Test
    @DisplayName("the Test Suite job has a timeout of its own, covering set-up and the uploads too")
    void theJobIsBounded() {
        String workflow = read(repoRoot().resolve(WORKFLOW));
        Matcher jobStart = Pattern.compile("(?m)^  test:\\s*$").matcher(workflow);
        assertTrue(jobStart.find(), "no 'test' job in " + WORKFLOW);
        int job = jobStart.start();
        int steps = workflow.indexOf("\n    steps:", job);
        assertBounded(workflow.substring(job, steps), "the test job");
    }

    private static void assertBounded(String block, String what) {
        Matcher timeout = TIMEOUT.matcher(block);
        assertTrue(timeout.find(), what + " sets no timeout-minutes, so a hung test holds the runner for "
                + GITHUB_DEFAULT_MINUTES + " minutes before anyone can read its log (#1516)");
        int minutes = Integer.parseInt(timeout.group(1));
        assertTrue(minutes >= SLOWEST_CORRECT_MINUTES && minutes < GITHUB_DEFAULT_MINUTES,
                what + " times out after " + minutes + " minutes; it must allow the slowest correct run ("
                        + SLOWEST_CORRECT_MINUTES + ") and stay well below GitHub's " + GITHUB_DEFAULT_MINUTES);
    }

    /** {@return the lines of the step named {@code name}, up to the next step} */
    private static String stepBody(String name) {
        String workflow = read(repoRoot().resolve(WORKFLOW));
        int start = workflow.indexOf("- name: " + name);
        assertTrue(start >= 0, "no step named '" + name + "' in " + WORKFLOW);
        int end = workflow.indexOf("\n      - name: ", start + 1);
        return workflow.substring(start, end < 0 ? workflow.length() : end);
    }

    private static String read(Path path) {
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
