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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow step that downloads a release asset must check it against a recorded SHA-256 (#867).
 *
 * <p>A release asset behind a tag can be replaced without the URL changing, so a pinned version in
 * the URL does not pin the bytes. {@code fuzzing.yml} downloaded the Jazzer CLI that way and ran it
 * against the library's classes; {@code demo.yml} already checked its {@code agg} binary with
 * {@code sha256sum -c}, which is the shape every such step now has to have.
 *
 * <p>The scan is textual and per step: a step whose text fetches a {@code releases/download/} URL
 * with {@code curl} or {@code wget} must also contain {@code sha256sum -c}.
 */
class WorkflowDownloadIntegrityTest {

    /** The first line of a step: a list item at any indent whose key starts a step. */
    private static final Pattern STEP_START =
            Pattern.compile("^\\s*- (?:name|uses|id|run|if|env|with|shell|working-directory):");

    @Test
    @DisplayName("every workflow step that downloads a release asset verifies its SHA-256")
    void everyReleaseDownloadIsHashChecked() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        int downloads = 0;
        for (Path file : workflows) {
            for (String step : steps(readLines(file))) {
                if (!downloadsAReleaseAsset(step)) {
                    continue;
                }
                downloads++;
                if (!step.contains("sha256sum -c")) {
                    findings.add(relativeName(file) + "  " + step.strip().lines().findFirst().orElse(""));
                }
            }
        }

        assertTrue(findings.isEmpty(),
                "These steps run a downloaded release asset without checking its bytes; a release "
                        + "asset can be replaced behind an unchanged URL (#867). Record the SHA-256 and "
                        + "add: echo \"<sha256>  <file>\" | sha256sum -c -\n  "
                        + String.join("\n  ", findings));
        assertTrue(downloads > 0,
                "No workflow step downloads a release asset, so the scan matched nothing. If that is "
                        + "now true, delete this test rather than let it pass vacuously.");
    }

    @Test
    @DisplayName("the scan finds an unverified download and accepts a verified one")
    void theScanSeesBothShapes() {
        List<String> workflow = List.of(
                "    steps:",
                "      - name: Download Jazzer CLI",
                "        run: |",
                "          curl -fsSL -o jazzer.tar.gz \\",
                "            https://github.com/x/y/releases/download/v1/jazzer.tar.gz",
                "          tar -xzf jazzer.tar.gz",
                "      - name: Install agg",
                "        run: |",
                "          curl -sL https://github.com/x/agg/releases/download/v1/agg -o agg",
                "          echo \"abc  agg\" | sha256sum -c -");
        List<String> steps = steps(workflow);
        assertEquals(3, steps.size(), "the header and the two steps: " + steps);
        assertTrue(downloadsAReleaseAsset(steps.get(1)) && !steps.get(1).contains("sha256sum -c"),
                "the unverified download must be seen as one");
        assertTrue(downloadsAReleaseAsset(steps.get(2)) && steps.get(2).contains("sha256sum -c"),
                "the verified download must be seen as one, and as verified");
    }

    private static boolean downloadsAReleaseAsset(String step) {
        return step.contains("releases/download/") && (step.contains("curl ") || step.contains("wget "));
    }

    /** {@return the text of each step, the lines before the first step kept as one chunk} */
    private static List<String> steps(List<String> lines) {
        List<String> steps = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (STEP_START.matcher(line).find() && current.length() > 0) {
                steps.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            steps.add(current.toString());
        }
        return steps;
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
