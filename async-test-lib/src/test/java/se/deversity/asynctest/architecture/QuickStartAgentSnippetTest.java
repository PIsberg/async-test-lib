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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's quick-start agent snippets are the build that CI runs, line for line.
 *
 * <p>Attaching the agent at JVM startup with {@code -javaagent:} is the route JEP 451 endorses:
 * loading it into a running JVM prints a warning on JDK 21 and later, and a future JDK will refuse
 * it. The startup route needs the build to name the agent jar's path, which is two plugin blocks in
 * Maven and a configuration in Gradle, and a snippet that is one character off attaches nothing and
 * reports nothing. So each line of the README's marked snippets must appear in
 * {@code consumer-fixture-langs/quickstart}, which {@code e2e-tests.yml} and
 * {@code gradle-tests.yml} build against the freshly built artifact and whose test fails unless the
 * agent arrived by {@code -javaagent:} and found the README's race. Versions differ by design (the
 * README pins a release, the fixture the working tree), so version text is normalised away.
 */
class QuickStartAgentSnippetTest {

    private static final String MAVEN_MARKER = "<!-- quickstart-agent-maven: kept equal to consumer-fixture-langs/quickstart/pom.xml -->";
    private static final String GRADLE_MARKER = "<!-- quickstart-agent-gradle: kept equal to consumer-fixture-langs/quickstart/build.gradle.kts -->";

    @Test
    @DisplayName("the README's Maven agent snippet is the quick-start fixture's build")
    void mavenSnippetIsTheFixturesBuild() {
        assertSnippetIsIn(MAVEN_MARKER, "consumer-fixture-langs/quickstart/pom.xml");
    }

    @Test
    @DisplayName("the README's Gradle agent snippet is the quick-start fixture's build")
    void gradleSnippetIsTheFixturesBuild() {
        assertSnippetIsIn(GRADLE_MARKER, "consumer-fixture-langs/quickstart/build.gradle.kts");
    }

    private static void assertSnippetIsIn(String marker, String fixture) {
        Path root = repoRoot();
        List<String> snippet = fencedBlockAfter(read(root.resolve("README.md")), marker);
        assertFalse(snippet.isEmpty(), "README.md has no fenced block after the marker " + marker);
        Set<String> build = read(root.resolve(fixture)).lines()
                .map(QuickStartAgentSnippetTest::normalised)
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (String line : snippet) {
            String n = normalised(line);
            if (!n.isEmpty() && !n.startsWith("<version>") && !build.contains(n)) {
                missing.add(line.strip());
            }
        }
        assertTrue(missing.isEmpty(), "README.md's quick-start snippet has lines " + fixture
                + " does not, so the snippet users copy is not the build CI runs: " + missing
                + ". Change both together; the fixture is what proves the snippet attaches the agent.");
    }

    /** Whitespace stripped, and any version (a release number or a build expression) made one token. */
    private static String normalised(String line) {
        return line.strip()
                .replaceAll(":\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9]+)?\"", ":VERSION\"")
                .replaceAll(":\\$\\{[^}]+}\"", ":VERSION\"");
    }

    private static List<String> fencedBlockAfter(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return List.of();
        }
        List<String> lines = text.substring(at + marker.length()).lines().toList();
        List<String> block = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                if (inside) {
                    return block;
                }
                inside = true;
            } else if (inside) {
                block.add(line);
            }
        }
        return List.of();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
