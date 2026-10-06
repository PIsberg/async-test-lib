package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.OsSensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test that can only fail on Windows or macOS runs on a leg that can block a merge (#907).
 *
 * <p>The full suite's Windows and macOS legs are {@code continue-on-error} and skip pull requests
 * (#484), so {@code LicenseValidationCacheDogfoodTest}, which guards a leak that only Windows
 * produces, could go back to red with no check failing anywhere. Tests & Build's
 * {@code os-sensitive} job runs the {@code @OsSensitive} classes on Windows and macOS on every
 * event, as an ordinary failing job. This pins the tag id, the classes known to need it, and the
 * job's shape, so none of the three can drift into a run that executes nothing and passes.
 */
class OsSensitiveTestsBlockOnEveryOsTest {

    private static final String WORKFLOW = ".github/workflows/tests.yml";
    private static final String JOB = "  os-sensitive:";

    /** Classes whose defect only reproduces off Linux; each one's javadoc says why. */
    private static final List<String> KNOWN = List.of(
            "se.deversity.asynctest.runner.LicenseValidationCacheDogfoodTest",
            "se.deversity.asynctest.runner.LicenseValidationCacheCrossJvmTest",
            "se.deversity.asynctest.runner.LicenseValidationCacheTransientReadTest");

    @Test
    @DisplayName("@OsSensitive carries the tag id the job selects")
    void tagIdMatchesTheJob() {
        assertEquals("os-sensitive", OsSensitive.class.getAnnotation(Tag.class).value());
    }

    @Test
    @DisplayName("the classes guarding platform-specific defects carry @OsSensitive")
    void knownClassesAreTagged() throws ClassNotFoundException {
        for (String name : KNOWN) {
            assertTrue(Class.forName(name).isAnnotationPresent(OsSensitive.class),
                    name + " guards a defect only Windows or macOS reproduces; tag it @OsSensitive");
        }
    }

    @Test
    @DisplayName("tests.yml runs the tag on Windows and macOS as a job that can fail")
    void theJobRunsTheTagOnWindowsAndMacosAndCanFail() {
        List<String> job = jobLines();
        String body = String.join("\n", job);
        assertTrue(body.contains("windows-latest") && body.contains("macos-latest"),
                "the job must run on windows-latest and macos-latest:\n" + body);
        assertTrue(body.contains("-Dgroups=os-sensitive"),
                "the job must select the os-sensitive tag:\n" + body);
        assertTrue(body.contains("surefire-reports"),
                "the job must fail when the tag selected nothing, so a lost tag cannot pass empty:\n"
                        + body);
        for (String line : job) {
            assertFalse(line.startsWith("    continue-on-error:"),
                    "a continue-on-error job cannot block a merge, which is the job's only purpose");
            assertFalse(line.startsWith("    if:"),
                    "the job must run on every event, pull requests included: " + line);
        }
    }

    /** {@return the lines of the {@code os-sensitive} job, up to the next job} */
    private static List<String> jobLines() {
        List<String> lines = read(repoRoot().resolve(WORKFLOW));
        int start = lines.indexOf(JOB);
        assertTrue(start >= 0, "no '" + JOB.trim() + "' job in " + WORKFLOW);
        int end = start + 1;
        while (end < lines.size() && !isJobHeader(lines.get(end))) {
            end++;
        }
        return lines.subList(start, end);
    }

    private static boolean isJobHeader(String line) {
        return line.length() > 2 && line.startsWith("  ") && line.charAt(2) != ' '
                && line.charAt(2) != '#' && line.endsWith(":");
    }

    private static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
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
