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
 * Every entry in a harden-runner {@code allowed-endpoints} list is a {@code host:port} pair.
 *
 * <p><strong>The failure this prevents, and it had already happened.</strong> The lists are
 * written as YAML folded scalars ({@code allowed-endpoints: >}), and inside a block scalar a
 * {@code #} line is <em>not</em> a comment: it is content. Three workflows had explanatory
 * comments indented inside the block, so the value the action received read
 * {@code ... objects.githubusercontent.com:443 # Only the JDKs the runner image already caches
 * are free. 26 is not cached, so # setup-java downloads it ...}.
 *
 * <p>harden-runner splits that on whitespace and resolves each token as a domain. It reached
 * {@code setup-java}, got NXDOMAIN, and exited:
 *
 * <pre>
 * Error resolving allowed domain ... unable to resolve domain setup-java., status 3
 * agent.service: Main process exited, code=exited, status=1/FAILURE
 * </pre>
 *
 * <p>Two consequences, and the quiet one is worse. The loud one is that the agent reverts its
 * network changes as it dies, and often enough that teardown leaves DNS unusable for the rest of
 * the job: Maven then fails in about a second with {@code Unresolveable build extension:
 * central-publishing-maven-plugin}, and {@code setup-java} fails with {@code getaddrinfo
 * EAI_AGAIN}. Both were read as network flakes and one of them (#339) was "fixed" with transfer
 * retries, which cannot help a build whose DNS is gone. The quiet one is that a dead agent blocks
 * nothing, so the step named "Block unexpected outbound calls" was doing nothing at all in
 * {@code tests.yml}, {@code e2e-tests.yml} and {@code fuzzing.yml} - on every run, not just the
 * ones that went red.
 *
 * <p>Nothing in a green build could have surfaced either. The agent's own failure is in a
 * collapsed log group, the job carries on, and the pipeline stays green while the protection it
 * advertises is off. That is why this is a committed check and not a note.
 */
class HardenRunnerEndpointsTest {

    /** The key whose value is a whitespace-separated endpoint list. */
    private static final Pattern ENDPOINTS_KEY =
            Pattern.compile("^(\\s*)allowed-endpoints:\\s*([>|].*)?$");

    /** What harden-runner can actually resolve: a host, or a wildcard host, and a port. */
    private static final Pattern ENDPOINT =
            Pattern.compile("^[A-Za-z0-9*][A-Za-z0-9.*_-]*:\\d{1,5}$");

    @Test
    @DisplayName("no harden-runner endpoint list contains anything but host:port entries")
    void everyAllowedEndpointIsAHostAndPort() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        int lists = 0;
        int entries = 0;

        for (Path file : workflows) {
            List<String> lines = readLines(file);
            String relative = repoRoot().relativize(file).toString().replace('\\', '/');
            for (int i = 0; i < lines.size(); i++) {
                var key = ENDPOINTS_KEY.matcher(lines.get(i));
                if (!key.matches()) {
                    continue;
                }
                lists++;
                int indent = key.group(1).length();
                for (int j = i + 1; j < lines.size(); j++) {
                    String line = lines.get(j);
                    if (line.isBlank() || indentOf(line) <= indent) {
                        break;
                    }
                    for (String token : line.strip().split("\\s+")) {
                        entries++;
                        if (!ENDPOINT.matcher(token).matches()) {
                            findings.add(relative + ":" + (j + 1) + "  " + token);
                        }
                    }
                }
            }
        }

        assertTrue(findings.isEmpty(),
                "These are inside a harden-runner allowed-endpoints list but are not host:port "
                        + "entries. A folded scalar has no comments - every one of these is sent "
                        + "to the agent as a domain to resolve, and the first one that fails "
                        + "NXDOMAIN kills the agent, which then blocks nothing and can take the "
                        + "job's DNS with it:\n  " + String.join("\n  ", findings)
                        + "\nMove the explanation above the allowed-endpoints: key, where YAML "
                        + "treats it as a comment.");

        assertTrue(lists > 0 && entries > 0,
                "Found " + lists + " endpoint lists and " + entries + " entries. Zero of either "
                        + "means the scan stopped matching and is checking nothing, which is not "
                        + "the same as a repository with no egress policy.");
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
