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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The example class every quick-start build section shows, and the fixture runs. */
    private static final String EXAMPLE_CLASS = "class CounterTest {";

    private static final Pattern INCLUDES = Pattern.compile("includes=([\\w.]+)");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * A user who copies the quick start verbatim must get a woven test. The agent weaves only classes
     * under the snippet's {@code includes=} prefix, and the example used to have no {@code package}
     * line at all: in the default package it was never woven, and reported nothing, while the
     * fixture, whose copy sat in a package, passed. So every quick-start example must declare a
     * package under the prefix, and the fixture's copy must use that same package.
     */
    @Test
    @DisplayName("the README's quick-start example sits in the package its agent snippet weaves")
    void theExampleIsInsideTheWovenPackage() {
        Path root = repoRoot();
        String readme = read(root.resolve("README.md"));
        String fixturePackage = packageOf(read(root.resolve(
                "consumer-fixture-langs/quickstart/src/test/java/com/example/CounterTest.java")));
        for (String marker : List.of(MAVEN_MARKER, GRADLE_MARKER)) {
            String snippet = String.join("\n", fencedBlockAfter(readme, marker));
            Matcher includes = INCLUDES.matcher(snippet);
            assertTrue(includes.find(), "the snippet after " + marker + " names no includes=");
            List<String> example = exampleAfter(readme, marker);
            assertFalse(example.isEmpty(), "no " + EXAMPLE_CLASS + " example follows " + marker);
            String examplePackage = packageOf(String.join("\n", example));
            assertTrue(examplePackage != null && (examplePackage + ".").startsWith(includes.group(1) + "."),
                    "the quick-start example after " + marker + " is in "
                            + (examplePackage == null ? "the default package" : "package " + examplePackage)
                            + ", which includes=" + includes.group(1) + " does not weave, so a user who"
                            + " copies it verbatim gets a run that reports nothing");
            assertEquals(examplePackage, fixturePackage,
                    "the fixture's CounterTest must be the README's example, package included");
        }
    }

    private static String packageOf(String source) {
        Matcher m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /** The first fenced block after {@code marker} that declares the example class. */
    private static List<String> exampleAfter(String text, String marker) {
        String rest = text.substring(text.indexOf(marker) + marker.length());
        int classAt = rest.indexOf(EXAMPLE_CLASS);
        if (classAt < 0) {
            return List.of();
        }
        int fence = rest.lastIndexOf("```", classAt);
        int lineStart = rest.lastIndexOf('\n', fence); // the fence is indented inside a list item
        return fencedBlockAfter(rest.substring(lineStart), "\n");
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
