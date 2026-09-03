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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow that runs on a pull request must cancel its own superseded runs.
 *
 * <p><strong>This had already happened.</strong> Between 2026-08-31 and 2026-09-03 the median
 * wall time of {@code Tests &amp; Build} grew about 23% with no individual job getting slower.
 * Nothing was slower; there was simply more of it in the queue at once. A force-push left the
 * previous run of every workflow alive, so two full fan-outs of the same branch competed for the
 * same concurrent-job allowance, and every job spent its extra minutes queued rather than
 * running (#484).
 *
 * <p>The two checks below are separate on purpose, because the ways this regresses are separate.
 * A new workflow file simply arrives without a {@code concurrency:} block, which the first check
 * catches. An existing one keeps its block but is given a group that does not vary per branch -
 * a bare {@code group: ${{ github.workflow }}}, say - which serialises every open pull request
 * behind one another instead of cancelling the stale run of one branch; that is the second.
 *
 * <p>Neither is visible from a pipeline. Queueing does not fail anything: every check still goes
 * green, just later, and the only symptom is a wall-clock number nobody is watching.
 */
class WorkflowConcurrencyTest {

    /**
     * Workflows exempt from the per-ref group rule, each with the reason it cannot take one.
     *
     * <p>An exemption is a claim that the workflow must serialise globally, not a note that
     * somebody did not get to it. Keep this list at the size where every entry can be defended.
     */
    private static final Map<String, String> GLOBAL_GROUP_ALLOWED = Map.of(
            "javadoc.yml",
            "GitHub allows one Pages deployment at a time, so group: pages is required and a "
                    + "half-applied site is worse than a queued one. It runs on a pull request "
                    + "only when the workflow or its script changes, so it is not fan-out.");

    @Test
    @DisplayName("every workflow triggered by a pull request declares concurrency")
    void everyPullRequestWorkflowDeclaresConcurrency() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        int scanned = 0;
        for (Path file : workflows) {
            List<String> lines = readLines(file);
            if (!triggersOnPullRequest(lines)) {
                continue;
            }
            scanned++;
            if (concurrencyValue(lines, "group") == null) {
                findings.add(relativeName(file));
            }
        }

        assertTrue(findings.isEmpty(),
                "These run on a pull request without a top-level concurrency: block, so a "
                        + "force-push starts a second full run while the first is still going "
                        + "and both compete for the same runner allowance. Nothing fails - every "
                        + "job just waits longer, which is why this went unnoticed for weeks "
                        + "(#484):\n  " + String.join("\n  ", findings)
                        + "\nAdd:\n  concurrency:\n    group: ${{ github.workflow }}-${{ "
                        + "github.ref }}\n    cancel-in-progress: ${{ github.event_name == "
                        + "'pull_request' }}");

        assertTrue(scanned > 0,
                "Found no workflow triggered by a pull request, so the scan matched nothing and "
                        + "checked nothing. That is a broken scan, not a clean repository.");
    }

    @Test
    @DisplayName("a concurrency group varies per branch, and cancels a superseded pull request")
    void concurrencyGroupIsPerRefAndCancels() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        int checked = 0;
        for (Path file : workflows) {
            List<String> lines = readLines(file);
            String group = concurrencyValue(lines, "group");
            if (!triggersOnPullRequest(lines) || group == null) {
                continue;
            }
            String name = file.getFileName().toString();
            if (GLOBAL_GROUP_ALLOWED.containsKey(name)) {
                continue;
            }
            checked++;
            if (!variesPerRef(group)) {
                findings.add(relativeName(file) + "  group: " + group
                        + "  (shared by every branch, so pull requests queue behind each other)");
            }
            String cancel = concurrencyValue(lines, "cancel-in-progress");
            if (cancel == null || "false".equals(cancel)) {
                findings.add(relativeName(file) + "  cancel-in-progress: " + cancel
                        + "  (a superseded run of this branch keeps its runners)");
            }
        }

        assertTrue(findings.isEmpty(),
                "A concurrency group must name the branch it belongs to and must cancel the run "
                        + "it supersedes. A group shared across branches serialises unrelated "
                        + "pull requests, and a run left alive after a force-push holds runners "
                        + "for a commit nobody will merge (#484):\n  "
                        + String.join("\n  ", findings)
                        + "\nIf a workflow genuinely must serialise globally - a Pages deploy "
                        + "does - add it to GLOBAL_GROUP_ALLOWED with the reason.");

        assertTrue(checked > 0,
                "Every pull-request workflow was exempt, so this check asserted nothing. An "
                        + "exemption list that covers the whole scan is not a passing gate.");
    }

    /** {@return whether {@code group} interpolates something that differs between branches} */
    private static boolean variesPerRef(String group) {
        return group.contains("github.ref")
                || group.contains("github.head_ref")
                || group.contains("github.event.pull_request.number")
                || group.contains("github.run_id");
    }

    /**
     * {@return whether the {@code on:} block names {@code pull_request}}
     *
     * <p>Both spellings occur here: a block mapping with {@code pull_request:} indented under
     * {@code on:}, and the flow sequence {@code on: [pull_request]} that dependency-review.yml
     * uses. A scan that only knew the first would silently skip the second and report clean.
     */
    private static boolean triggersOnPullRequest(List<String> lines) {
        boolean inOn = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("#")) {
                continue;
            }
            if (line.startsWith("on:")) {
                if (stripped.substring("on:".length()).contains("pull_request")) {
                    return true;
                }
                inOn = true;
                continue;
            }
            if (inOn) {
                if (!line.isBlank() && !line.startsWith(" ")) {
                    return false;
                }
                if (indentOf(line) == 2 && stripped.startsWith("pull_request:")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return the value of {@code key} inside the top-level {@code concurrency:} block, or null}
     *
     * <p>Only the top-level block counts. A {@code concurrency:} nested inside a job limits that
     * one job and leaves the rest of the fan-out running.
     */
    private static String concurrencyValue(List<String> lines, String key) {
        boolean inBlock = false;
        for (String line : lines) {
            if (line.startsWith("concurrency:")) {
                inBlock = true;
                continue;
            }
            if (!inBlock) {
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ")) {
                return null;
            }
            String stripped = line.strip();
            if (stripped.startsWith("#")) {
                continue;
            }
            if (stripped.startsWith(key + ":")) {
                return stripped.substring(key.length() + 1).strip();
            }
        }
        return null;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String relativeName(Path file) {
        StringBuilder out = new StringBuilder();
        for (Path part : repoRoot().relativize(file)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    private static List<Path> yamlFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + root, e);
        }
    }

    private static List<String> readLines(Path path) {
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
