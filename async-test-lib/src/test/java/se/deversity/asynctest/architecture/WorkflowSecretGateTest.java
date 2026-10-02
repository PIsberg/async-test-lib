package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow that runs only when a secret exists must skip the job, not each of its steps (#868).
 *
 * <p>A job whose steps all carry {@code if: env.HAVE_KEY == 'true'} still runs when the key is
 * missing: every step is skipped, and the job reports success. Branch protection reads that as a
 * passed check, so the day such a job is made required, a missing or expired secret makes the
 * required check pass instead of block. A skipped job is the honest signal, and a job-level
 * {@code if:} cannot read {@code secrets}, so the presence goes through a preflight job's output:
 * {@code needs: preflight} and {@code if: needs.preflight.outputs.has-key == 'true'}.
 *
 * <p>The scan is textual: it collects every env variable defined as a secret-presence test
 * ({@code ${{ secrets.X != '' }}}) and refuses any {@code if:} that reads one. Computing the
 * presence in a preflight step's env and echoing it into {@code $GITHUB_OUTPUT} reads it in a
 * {@code run:}, never an {@code if:}, so it passes.
 */
class WorkflowSecretGateTest {

    /** An env entry whose value is whether a secret is set, such as {@code HAVE_KEY: ${{ secrets.K != '' }}}. */
    private static final Pattern PRESENCE_ENV = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*):\\s*\\$\\{\\{\\s*secrets\\.[A-Za-z0-9_]+\\s*!=\\s*''\\s*}}\\s*$");

    private static final Pattern IF_LINE = Pattern.compile("^\\s*(?:-\\s+)?if:\\s*(.*)$");

    @Test
    @DisplayName("no workflow gates its steps on whether a secret is set")
    void noStepIsGatedOnASecretsPresence() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        for (Path file : workflows) {
            for (String finding : stepGatesOnSecretPresence(readLines(file))) {
                findings.add(relativeName(file) + "  " + finding);
            }
        }

        assertTrue(findings.isEmpty(),
                "These steps are skipped when a secret is missing, but their job still runs and "
                        + "reports success, which a required check reads as a pass (#868). Move the "
                        + "presence into a preflight job's output and gate the job with "
                        + "needs: preflight and if: needs.preflight.outputs.has-key == 'true':\n  "
                        + String.join("\n  ", findings));
    }

    @Test
    @DisplayName("the scan catches the shape #868 described, so a clean result means something")
    void theScanCatchesStepsGatedOnAPresenceEnv() {
        List<String> before = List.of(
                "jobs:",
                "  inquisitor:",
                "    env:",
                "      HAVE_KEY: ${{ secrets.ANTHROPIC_API_KEY != '' }}",
                "    steps:",
                "      - name: Note when the key is absent",
                "        if: env.HAVE_KEY != 'true'",
                "      - uses: actions/checkout@v4",
                "        if: env.HAVE_KEY == 'true'");
        assertEquals(2, stepGatesOnSecretPresence(before).size(),
                "both gated steps must be found, or the clean scan above proves nothing");

        List<String> after = List.of(
                "jobs:",
                "  preflight:",
                "    outputs:",
                "      has-key: ${{ steps.key.outputs.has-key }}",
                "    steps:",
                "      - id: key",
                "        env:",
                "          HAS_KEY: ${{ secrets.ANTHROPIC_API_KEY != '' }}",
                "        run: echo \"has-key=$HAS_KEY\" >> \"$GITHUB_OUTPUT\"",
                "  inquisitor:",
                "    needs: preflight",
                "    if: needs.preflight.outputs.has-key == 'true'");
        assertEquals(List.of(), stepGatesOnSecretPresence(after),
                "a presence echoed into a job output and a job gated on it is the fix, not a finding");
    }

    /** {@return each {@code if:} in {@code lines} that reads an env variable holding a secret's presence} */
    private static List<String> stepGatesOnSecretPresence(List<String> lines) {
        Set<String> presenceVars = new HashSet<>();
        for (String line : lines) {
            Matcher m = PRESENCE_ENV.matcher(line);
            if (m.matches()) {
                presenceVars.add(m.group(1));
            }
        }
        List<String> findings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = IF_LINE.matcher(lines.get(i));
            if (!m.matches()) {
                continue;
            }
            for (String var : presenceVars) {
                if (Pattern.compile("\\benv\\." + Pattern.quote(var) + "\\b").matcher(m.group(1)).find()) {
                    findings.add("line " + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        return findings;
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
