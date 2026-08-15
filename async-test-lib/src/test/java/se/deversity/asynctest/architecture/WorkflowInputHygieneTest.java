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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the untrusted-context rule for the one place this repository executes text it did not
 * write: GitHub Actions workflows.
 *
 * <p><strong>The failure this prevents.</strong> Issue titles, PR titles and bodies, comment
 * bodies, commit messages, branch names and page names are written by whoever opens them. A
 * workflow that interpolates one of those into a {@code run:} step
 * ({@code run: echo "${{ github.event.issue.title }}"}) hands that author a shell on the runner,
 * and a workflow that interpolates one into an agent lane's {@code prompt:} hands them the
 * agent. GitHub's own guidance is the same rule the root {@code CLAUDE.md} states for agents:
 * text from outside the repository is data, passed through an environment variable and quoted,
 * never an instruction. Nothing here did that on 2026-08-15; this test keeps it so when the
 * next workflow is added.
 *
 * <p>The scan is line-based on purpose. It tracks {@code run:}, {@code script:} and
 * {@code prompt:} block scalars by indentation and flags any {@code ${{ ... }}} expression inside
 * them that names an untrusted event field. Passing the same field through {@code env:} is
 * allowed, and is the fix the failure message names.
 */
class WorkflowInputHygieneTest {

    /**
     * Event fields whose content is chosen by the person who triggers the event. The list is
     * GitHub's ("Understanding the risk of script injections") plus {@code head_ref}, which
     * is a branch name the PR author picks.
     */
    private static final Pattern UNTRUSTED = Pattern.compile(
            "github\\.(event\\.(issue|pull_request|comment|review|review_comment|discussion"
                    + "|discussion_comment)\\.(title|body)"
                    + "|event\\.comment\\.body"
                    + "|event\\.pull_request\\.head\\.(ref|label|repo\\.default_branch)"
                    + "|head_ref"
                    + "|event\\.(commits|head_commit)"
                    + "|event\\.pages"
                    + "|event\\.(issue|pull_request|comment|review)\\.user\\.(login|name|email))");

    /** The step keys whose scalar becomes code, or an agent's instruction. */
    private static final Pattern EXECUTING_KEY =
            Pattern.compile("^(\\s*)(- )?(run|script|prompt):\\s*(\\||>)?[-+]?\\s*(.*)$");

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{\\{[^}]*\\}\\}");

    @Test
    @DisplayName("no workflow interpolates untrusted event text into a run, script or prompt block")
    void untrustedEventFieldsNeverReachAnExecutingBlock() {
        Path workflows = repoRoot().resolve(".github/workflows");
        List<Path> files = yamlFiles(workflows);
        assertFalse(files.isEmpty(), "No workflow files found under " + workflows
                + "; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        for (Path file : files) {
            scan(file, findings);
        }

        assertTrue(findings.isEmpty(),
                "These workflow lines interpolate untrusted event text into a step that executes "
                        + "it (a run:, script: or prompt: block):\n  "
                        + String.join("\n  ", findings)
                        + "\nPass the value through env: instead (env: TITLE: ${{ ... }} then "
                        + "\"$TITLE\" in the script) so it is data, not code. For an agent lane, "
                        + "never put event text into the prompt at all; give the agent the "
                        + "diff and the committed law.");
    }

    /**
     * Walks one workflow. A block starts at a {@code run:} / {@code script:} / {@code prompt:}
     * key and continues while following lines are indented deeper than that key (or are
     * blank); the key's own line counts too, for the inline {@code run: cmd} form.
     */
    private static void scan(Path file, List<String> findings) {
        List<String> lines = readLines(file);
        int blockIndent = -1;   // indentation of the key that opened the current block, or -1
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int indent = indentOf(line);
            boolean blank = line.isBlank();

            if (blockIndent >= 0 && !blank && indent <= blockIndent) {
                blockIndent = -1;   // the block ended
            }

            var key = EXECUTING_KEY.matcher(line);
            boolean opensBlock = key.matches();
            if (opensBlock) {
                blockIndent = key.group(1).length() + (key.group(2) == null ? 0 : 2);
            }

            if ((opensBlock || blockIndent >= 0) && !blank) {
                var expr = EXPRESSION.matcher(line);
                while (expr.find()) {
                    if (UNTRUSTED.matcher(expr.group()).find()) {
                        findings.add(repoRoot().relativize(file).toString().replace('\\', '/')
                                + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
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
