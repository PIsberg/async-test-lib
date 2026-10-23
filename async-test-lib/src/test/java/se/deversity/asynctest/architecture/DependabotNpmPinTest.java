package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Copilot CLI pin that {@code instruction-evals.yml} installs is one Dependabot can update (#1029).
 *
 * <p>With the pin under {@code .github/copilot-cli}, every Dependabot npm run resolved the update and
 * then had its {@code create_pull_request} refused with a 400 ("The request contains invalid or
 * unauthorized changes"), so no PR was ever opened and the CLI stayed on 1.0.87 while 1.0.98
 * shipped. The same pin pattern in vibetags, at {@code /tools/codex-cli}, gets its PRs. So an npm
 * entry stays out of {@code .github/}, and the directory the workflow installs from is the one
 * Dependabot watches.
 */
class DependabotNpmPinTest {

    private static final Pattern DIRECTORY = Pattern.compile("^\\s*(?:directory:|-)\\s*(/[^\\s#]*)");
    private static final Pattern NPM_CI = Pattern.compile("npm ci --prefix (\\S+)");

    @Test
    @DisplayName("no Dependabot npm entry points under .github/")
    void npmEntriesStayOutOfDotGithub() {
        List<String> dirs = npmDirectories(read(repoRoot().resolve(".github/dependabot.yml")));
        assertFalse(dirs.isEmpty(), "no Dependabot npm entry; the Copilot CLI pin would go unwatched");
        for (String dir : dirs) {
            assertFalse(dir.startsWith("/.github/"), "Dependabot npm entry " + dir + " is under .github/,"
                    + " where every create_pull_request was refused with a 400 (#1029)");
            assertTrue(Files.isRegularFile(repoRoot().resolve(dir.substring(1)).resolve("package-lock.json")),
                    "Dependabot npm entry " + dir + " has no package-lock.json");
        }
    }

    @Test
    @DisplayName("instruction-evals installs the Copilot CLI from a directory Dependabot watches")
    void workflowInstallsFromTheWatchedPin() {
        Path root = repoRoot();
        Matcher m = NPM_CI.matcher(read(root.resolve(".github/workflows/instruction-evals.yml")));
        assertTrue(m.find(), "instruction-evals.yml no longer runs npm ci --prefix; update this test");
        String prefix = "/" + m.group(1);
        assertTrue(npmDirectories(read(root.resolve(".github/dependabot.yml"))).contains(prefix),
                "instruction-evals.yml installs from " + prefix + ", which no Dependabot npm entry watches");
    }

    /** {@return the directories of every {@code npm} entry, read line by line} */
    private static List<String> npmDirectories(String yaml) {
        List<String> dirs = new ArrayList<>();
        boolean npm = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.strip().startsWith("- package-ecosystem:")) {
                npm = line.contains("npm");
                continue;
            }
            Matcher d = DIRECTORY.matcher(line);
            if (npm && d.find()) {
                String dir = d.group(1);
                dirs.add(dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir);
            }
        }
        return dirs;
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
