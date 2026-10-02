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
