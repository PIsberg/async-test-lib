package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guardrail-drift check runs from one script, in CI and in the local pre-commit hook (#869).
 *
 * <p>Before #869 the regenerate-and-diff lived only in {@code guardrails.yml}, so an annotation
 * changed without regenerating, or a hand edit inside {@code VIBETAGS-START}/{@code END}, surfaced
 * one CI round-trip after the push. A local hook with its own copy of the logic would drift from
 * CI's on the one thing that matters, which files count. So both call
 * {@code tools/guardrail-drift.sh}, the path list lives only there, and this test holds both
 * callers to it.
 */
class GuardrailDriftWiringTest {

    private static final String SCRIPT = "tools/guardrail-drift.sh";

    @Test
    @DisplayName("the CI Guardrail Drift job runs the shared script, byte-exact")
    void ciRunsTheScript() throws IOException {
        String workflow = read(".github/workflows/guardrails.yml");
        assertTrue(workflow.contains("run: sh " + SCRIPT + "\n"),
                "guardrails.yml must run " + SCRIPT + " with no options, so CI stays byte-exact");
        assertFalse(workflow.contains("paths=\"CLAUDE.md"),
                "the guardrail path list must live only in " + SCRIPT + ", or CI and the hook can "
                        + "disagree on which files count");
    }

    @Test
    @DisplayName("the pre-commit config runs the same script as a local hook")
    void preCommitRunsTheScript() throws IOException {
        String config = read(".pre-commit-config.yaml");
        assertTrue(config.contains("id: guardrail-drift"), "a guardrail-drift hook must be declared");
        assertTrue(config.contains("entry: sh " + SCRIPT),
                "the hook must call " + SCRIPT + ", not a copy of its logic");
    }

    @Test
    @DisplayName("the script holds the path list and the regeneration")
    void theScriptRegeneratesAndDiffs() throws IOException {
        String script = read(SCRIPT);
        assertTrue(script.contains("mvn -B -q clean test-compile"),
                "test-compile, not compile: the processor also runs over the test sources");
        for (String path : new String[] {"CLAUDE.md", "GEMINI.md", ".vibetags-mod-async-test-lib",
                "async-test-agent/.claude/rules", "async-test-analysis/CLAUDE.md"}) {
            assertTrue(script.contains(path), "the script's path list must include " + path);
        }
        int listPaths = script.indexOf("\"--list-paths\" ]; then");
        assertTrue(listPaths >= 0 && listPaths < script.indexOf("mvn -B -q clean test-compile"),
                "--list-paths must print the path list before any build runs: "
                        + "tools/dependabot-regen.sh calls it in the job that must not build");
    }

    /**
     * The Dependabot regeneration (#1010 merged red because a vibetags bump needs a regeneration)
     * pushes with a token that can write to the repository. Its safety is the shape pinned here:
     * the job that builds the pull request holds no secret, the job that holds the token runs only
     * main's copy of the apply script, and that script takes its allowed files from this script's
     * path list rather than a copy.
     */
    @Test
    @DisplayName("the Dependabot regeneration pushes only guardrail files, from a job that runs no PR code")
    void dependabotRegenerationIsConfinedToTheGuardrailFiles() throws IOException {
        String workflow = read(".github/workflows/dependabot-regen.yml");
        String regen = read("tools/dependabot-regen.sh");
        int pushJob = workflow.indexOf("\n  push:\n");
        assertTrue(pushJob > 0, "dependabot-regen.yml must have a separate push job");
        String build = workflow.substring(0, pushJob);
        String push = workflow.substring(pushJob);

        assertTrue(build.contains("sh " + SCRIPT), "the regeneration must be the drift check's own");
        assertFalse(build.contains("token: ${{ secrets."),
                "the job that builds the pull request must not check out with a write token");
        assertFalse(push.contains("sh tools/") || push.contains("mvn ") || push.contains("./gradlew"),
                "the job holding the push token must not run anything from the pull request's tree");
        assertTrue(push.contains("sh base/tools/dependabot-regen.sh apply head"),
                "the push job must apply the patch with the base branch's copy of the script");
        assertTrue(push.contains("ref: ${{ github.event.pull_request.base.sha }}"),
                "base/ must be the base branch, not the pull request");
        assertTrue(push.contains("dependabot/*)"), "the push must refuse a branch Dependabot does not own");
        assertTrue(push.contains("[dependabot skip]"),
                "without [dependabot skip] Dependabot stops rebasing a PR somebody else committed to");
        assertTrue(workflow.contains("github.event.pull_request.user.login == 'dependabot[bot]'"),
                "only Dependabot's own pull requests may be regenerated and pushed to");

        assertTrue(regen.contains("guardrail-drift.sh\" --list-paths"),
                "the files a regeneration may push must be read from " + SCRIPT + ", never copied");
        assertFalse(regen.contains("paths=\"CLAUDE.md"), "the path list must live only in " + SCRIPT);
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot().resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " must exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve(".github"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }
}
