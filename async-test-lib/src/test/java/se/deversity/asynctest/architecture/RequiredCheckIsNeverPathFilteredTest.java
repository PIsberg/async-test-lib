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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one way a path filter can go wrong: putting a required status check behind one.
 *
 * <p><strong>The failure this prevents.</strong> A branch-protection rule waits for a named
 * context. If the workflow that reports that context is skipped because the diff matched a
 * {@code paths-ignore} entry, the context never arrives, and GitHub does not treat "never ran"
 * as "passed" - the pull request sits at BLOCKED with every visible check green and no way
 * forward but an admin merge. That is strictly worse than the fan-out the filters exist to
 * reduce, which is why #484's path filters were applied only to workflows carrying no required
 * context, and why this test exists to keep it that way.
 *
 * <p>The contexts below are {@code main}'s required checks, read from the branch-protection API
 * on 2026-09-06. They are duplicated here on purpose: the API is not reachable from a test, and
 * a stale list here fails in the safe direction - it can only over-protect a workflow that has
 * stopped being required, never let a filter onto one that still is. When a required check is
 * added or removed, update this list in the same change.
 */
class RequiredCheckIsNeverPathFilteredTest {

    /** Job names, without the matrix suffix GitHub appends to build the context. */
    private static final Set<String> REQUIRED_JOB_NAMES = Set.of(
            "Build Maven Project",
            "Gradle Test Suite",
            "Test Suite",
            "Guardrail Drift",
            "Locked Files Guard",
            "Architecture Diagram Drift");

    private static final Pattern JOB_NAME = Pattern.compile("^\s{4}name:\s*(.+?)\s*$");
    private static final Pattern PATH_FILTER = Pattern.compile("^\s+paths(-ignore)?:\s*$");

    @Test
    @DisplayName("no workflow that reports a required check filters itself out by path")
    void requiredChecksAlwaysReport() {
        List<String> offenders = new ArrayList<>();
        Path workflows = repoRoot().resolve(".github/workflows");
        try (Stream<Path> files = Files.list(workflows)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                List<String> lines = read(file);
                if (reportsARequiredCheck(lines) && hasPathFilter(lines)) {
                    offenders.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + workflows, e);
        }

        assertTrue(offenders.isEmpty(),
                "These workflows report a check that main's branch protection requires, and also "
                        + "filter themselves out by path. A diff that matches the filter leaves "
                        + "the required context unreported, and a pull request whose required "
                        + "check never ran is BLOCKED forever, not passing - every other check "
                        + "green and no way to merge (#484). Either drop the filter, or add a "
                        + "companion job that reports the same context as a skip-with-success. "
                        + "Offenders: " + offenders);
    }

    private static boolean reportsARequiredCheck(List<String> lines) {
        for (String line : lines) {
            Matcher m = JOB_NAME.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).replace("\"", "").replace("'", "");
            for (String required : REQUIRED_JOB_NAMES) {
                // startsWith, because a matrix job declares "Test Suite" and GitHub renders the
                // context as "Test Suite (21, ubuntu-latest)".
                if (name.startsWith(required)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only the trigger block matters; a {@code paths} key deeper in a step is something else. */
    private static boolean hasPathFilter(List<String> lines) {
        boolean inTriggers = false;
        for (String line : lines) {
            if (line.startsWith("jobs:")) {
                return false;
            }
            if (line.startsWith("on:")) {
                inTriggers = true;
                continue;
            }
            if (inTriggers && !line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                inTriggers = false;
            }
            if (inTriggers && PATH_FILTER.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve(".github"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding pom.xml and .github/) above "
                        + Path.of("").toAbsolutePath());
    }
}
