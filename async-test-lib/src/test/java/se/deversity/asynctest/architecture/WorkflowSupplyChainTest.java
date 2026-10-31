package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supply-chain properties of {@code .github/workflows/} that held when #1148 audited them, kept
 * from regressing.
 *
 * <p>The audit found every third-party action pinned to a commit, every workflow defaulting its token
 * to read-only, no trigger that runs pull-request code with the base repository's secrets, and every
 * harden-runner step blocking egress. None of that was enforced: OpenSSF Scorecard reports the first
 * two weekly and blocks nothing, so a tag-pinned action or a new {@code contents: write} would have
 * merged green. Each test below states one property and fails naming the line that breaks it.
 */
class WorkflowSupplyChainTest {

    private static final Pattern USES =
            Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*['\"]?([^'\"\\s#]+)");
    private static final Pattern PINNED =
            Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$");
    private static final Pattern WRITE_SCOPE = Pattern.compile("^\\s+([a-z-]+):\\s*write\\b");
    private static final Pattern UNTRUSTED_TRIGGER =
            Pattern.compile("\\b(pull_request_target|workflow_run|issue_comment)\\b");

    /**
     * Job-level write scopes, each reviewed in #1148: workflow file and scope. Exact, so a removed
     * grant has to leave this list too, and the list never describes more power than exists.
     */
    private static final Set<String> REVIEWED_WRITE_SCOPES = Set.of(
            "codeql.yml security-events",      // uploads SARIF to code scanning
            "copilot-review.yml pull-requests", // requests the review; label-gated, fork tokens are read-only
            "demo.yml contents",               // pushes the recording branch; schedule and dispatch only
            "demo.yml pull-requests",          // opens the PR carrying it
            "inquisitor.yml id-token",         // OIDC for the review action; no repository write
            "javadoc.yml pages",               // deploy job, skipped on pull_request
            "javadoc.yml id-token",
            "publish.yml contents",            // the release; push of a tag only
            "publish.yml id-token",            // keyless cosign
            "publish.yml attestations",        // build provenance (#961)
            "scorecards.yml security-events",
            "scorecards.yml id-token");

    @Test
    void everyActionIsPinnedToAFullCommitSha() {
        List<String> unpinned = new ArrayList<>();
        int checked = 0;
        for (Path file : workflows()) {
            List<String> lines = lines(file);
            for (int n = 0; n < lines.size(); n++) {
                Matcher uses = USES.matcher(lines.get(n));
                if (!uses.find() || uses.group(1).startsWith("./")) {
                    continue;
                }
                checked++;
                if (!PINNED.matcher(uses.group(1)).matches()) {
                    unpinned.add(file.getFileName() + ":" + (n + 1) + " " + uses.group(1));
                }
            }
        }
        assertTrue(unpinned.isEmpty(), "A tag or branch can be moved to different code after review; "
                + "pin these to a 40-character commit SHA, with the version in a trailing comment:\n  "
                + String.join("\n  ", unpinned));
        assertTrue(checked >= 50,
                "Matched only " + checked + " uses: lines across the workflows; the scan stopped "
                        + "matching, which is not a pass.");
    }

    @Test
    void everyWorkflowDefaultsItsTokenToReadOnly() {
        List<String> problems = new ArrayList<>();
        for (Path file : workflows()) {
            List<String> lines = lines(file);
            int at = -1;
            for (int n = 0; n < lines.size(); n++) {
                if (lines.get(n).startsWith("permissions:")) {
                    at = n;
                    break;
                }
            }
            if (at < 0) {
                problems.add(file.getFileName() + ": no top-level permissions, so every job gets the "
                        + "repository's default token");
                continue;
            }
            String inline = stripComment(lines.get(at).substring("permissions:".length())).strip();
            if (!inline.isEmpty()) {
                if (!inline.equals("read-all") && !inline.equals("{}")) {
                    problems.add(file.getFileName() + ": top-level permissions: " + inline);
                }
                continue;
            }
            for (int n = at + 1; n < lines.size() && isIndentedOrBlank(lines.get(n)); n++) {
                String entry = stripComment(lines.get(n)).strip();
                if (!entry.isEmpty() && !entry.endsWith(": read") && !entry.endsWith(": none")) {
                    problems.add(file.getFileName() + ":" + (n + 1) + " top-level " + entry
                            + "; grant writes on the job that needs them");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void jobWriteScopesAreExactlyTheReviewedOnes() {
        Set<String> found = new TreeSet<>();
        for (Path file : workflows()) {
            for (String line : lines(file)) {
                Matcher write = WRITE_SCOPE.matcher(stripComment(line));
                if (write.find()) {
                    found.add(file.getFileName() + " " + write.group(1));
                }
            }
        }
        assertEquals(new TreeSet<>(REVIEWED_WRITE_SCOPES), found,
                "A job-level write scope was added or removed. A new one widens what a compromised "
                        + "step can do with the token: review its trigger and the job's condition, "
                        + "then list it in REVIEWED_WRITE_SCOPES with why. A removed one leaves here too.");
    }

    @Test
    void noTriggerRunsPullRequestCodeWithBaseRepositorySecrets() {
        List<String> found = new ArrayList<>();
        for (Path file : workflows()) {
            List<String> lines = lines(file);
            for (int n = 0; n < lines.size(); n++) {
                Matcher trigger = UNTRUSTED_TRIGGER.matcher(stripComment(lines.get(n)));
                if (trigger.find()) {
                    found.add(file.getFileName() + ":" + (n + 1) + " " + trigger.group(1));
                }
            }
        }
        assertTrue(found.isEmpty(), "pull_request_target, workflow_run and issue_comment run with the "
                + "base repository's secrets and a writable token on behalf of anyone who can open a "
                + "PR or comment, which is the shape of most workflow compromises. None is used today; "
                + "adding one needs a design review, not just this test changed:\n  "
                + String.join("\n  ", found));
    }

    @Test
    void everyHardenRunnerStepBlocksEgress() {
        List<String> auditing = new ArrayList<>();
        int checked = 0;
        for (Path file : workflows()) {
            List<String> lines = lines(file);
            for (int n = 0; n < lines.size(); n++) {
                if (!stripComment(lines.get(n)).contains("step-security/harden-runner@")) {
                    continue;
                }
                checked++;
                boolean blocks = false;
                for (int k = n + 1; k < Math.min(lines.size(), n + 8); k++) {
                    if (stripComment(lines.get(k)).strip().equals("egress-policy: block")) {
                        blocks = true;
                        break;
                    }
                }
                if (!blocks) {
                    auditing.add(file.getFileName() + ":" + (n + 1));
                }
            }
        }
        assertTrue(auditing.isEmpty(), "These harden-runner steps do not set egress-policy: block in "
                + "their with: block; audit mode records exfiltration instead of stopping it:\n  "
                + String.join("\n  ", auditing));
        assertTrue(checked >= 40, "Matched only " + checked + " harden-runner steps; the scan stopped "
                + "matching, which is not a pass.");
    }

    private static String stripComment(String line) {
        if (line.strip().startsWith("#")) {
            return "";
        }
        int hash = line.indexOf(" #");
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static boolean isIndentedOrBlank(String line) {
        return line.isBlank() || line.startsWith(" ") || line.startsWith("#");
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> workflows() {
        Path dir = repoRoot().resolve(".github/workflows");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> found = files.filter(f -> f.toString().endsWith(".yml")
                    || f.toString().endsWith(".yaml")).sorted().toList();
            assertFalse(found.isEmpty(), "no workflows under " + dir);
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
