package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the facts that {@code pom.xml} and {@code build.gradle.kts} have to agree on.
 *
 * <p><strong>Versions are no longer mirrored, they are read.</strong> Every shared version used to
 * be written twice, once in each build, kept equal by a comment asking people to remember. That
 * failed repeatedly: the comment in {@code build.gradle.kts} recorded that spotbugs, error-prone
 * and pmd had each drifted. It also lagged by construction, because Dependabot raises its update
 * PRs against {@code pom.xml} only, so every bump landed in Maven and left the Gradle copy behind
 * until somebody noticed.
 *
 * <p>{@code build.gradle.kts} now parses {@code pom.xml}'s {@code <properties>} block at
 * configuration time and derives the versions from it, so there is no second copy to drift. What
 * this test checks is that the single source actually holds: that no shared version has been
 * re-hardcoded into a Gradle file, that the derivation is still in place rather than quietly
 * unwound, and that the coordinates are declared in one place.
 *
 * <p>It also pins the published description of each module, which is genuinely duplicated because
 * neither build can compute prose, and the detector count inside the library's description, which
 * is derived from the enum rather than restated.
 */
class BuildMetadataSyncTest {

    /** The three published modules, each with a pom and a Gradle script that must agree. */
    private static final List<String> PUBLISHED_MODULES =
            List.of("async-test-lib", "async-test-agent", "async-test-analysis");

    /**
     * Gradle-only versions, with the reason each has no Maven twin. Everything else has to come
     * from {@code pom.xml}. Versions in the {@code plugins} block are not covered here: those are
     * Gradle plugins with no Maven equivalent at all, and the gradle Dependabot ecosystem watches
     * them.
     */
    private static final Map<String, String> GRADLE_ONLY_VERSIONS = Map.of(
            "logbackVersion",
            "test-only SLF4J backend; Maven's test run has no binding, so there is no pom twin");

    /**
     * Lower bound on how many versions must be derived from the pom. Without it, a derivation
     * that stopped matching would leave every assertion here passing while checking nothing.
     */
    private static final int MIN_DERIVED_VERSIONS = 15;

    /** Matches an {@code extra["xVersion"] = "1.2.3"} literal assignment. */
    private static final Pattern GRADLE_VERSION_LITERAL = Pattern.compile(
            "^\\s*extra\\[\"([^\"]+)\"\\]\\s*=\\s*\"([^\"]*\\d[^\"]*)\"", Pattern.MULTILINE);

    /** Matches a hardcoded {@code "group:artifact:1.2.3"} coordinate in a Gradle file. */
    private static final Pattern GRADLE_PINNED_COORDINATE = Pattern.compile(
            "\"[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:\\d[A-Za-z0-9._-]*\"");

    /** Matches a {@code pomVersion("some.property")} lookup in the Gradle build. */
    private static final Pattern POM_VERSION_LOOKUP = Pattern.compile(
            "pomVersion\\(\"([^\"]+)\"\\)");

    private static final Pattern POM_PROPERTY = Pattern.compile(
            "<([A-Za-z0-9._-]+)>([^<>]*)</\\1>");

    @Test
    @DisplayName("no shared version is restated as a literal in a Gradle file")
    void sharedVersionsAreReadFromThePomRatherThanRestated() {
        Path root = repoRoot();
        List<String> restated = new ArrayList<>();

        Matcher m = GRADLE_VERSION_LITERAL.matcher(read(root.resolve("build.gradle.kts")));
        while (m.find()) {
            if (!GRADLE_ONLY_VERSIONS.containsKey(m.group(1))) {
                restated.add("build.gradle.kts: extra[\"" + m.group(1) + "\"] = \""
                        + m.group(2) + "\"");
            }
        }

        for (String module : PUBLISHED_MODULES) {
            Matcher c = GRADLE_PINNED_COORDINATE.matcher(
                    read(root.resolve(module + "/build.gradle.kts")));
            while (c.find()) {
                restated.add(module + "/build.gradle.kts: " + c.group());
            }
        }

        assertTrue(restated.isEmpty(),
                "These pin a version in a Gradle file instead of reading it from pom.xml: "
                        + restated + ". pom.xml is the single source, and it is the file Dependabot "
                        + "raises update PRs against, so a number written here stops receiving them "
                        + "and starts lagging. Add the property to pom.xml and use pomVersion(...). "
                        + "If it genuinely has no Maven twin, add it to GRADLE_ONLY_VERSIONS with "
                        + "the reason.");
    }

    @Test
    @DisplayName("the Gradle build really does read the pom, rather than passing vacuously")
    void gradleDerivesItsVersionsFromThePom() {
        Path root = repoRoot();
        String gradle = read(root.resolve("build.gradle.kts"));

        assertTrue(gradle.contains("providers.fileContents"),
                "build.gradle.kts no longer reads pom.xml. If the derivation was replaced, the "
                        + "literal check above passes while the versions are defined somewhere this "
                        + "test does not look.");

        Map<String, String> pomProps = pomProperties(read(root.resolve("pom.xml")));
        Matcher m = POM_VERSION_LOOKUP.matcher(gradle);
        int derived = 0;
        while (m.find()) {
            derived++;
            assertTrue(pomProps.containsKey(m.group(1)),
                    "build.gradle.kts reads pom property <" + m.group(1) + ">, which pom.xml does "
                            + "not define. The Gradle build fails at configuration time on this, so "
                            + "it is a real break rather than a style point.");
        }

        assertTrue(derived >= MIN_DERIVED_VERSIONS,
                "Only " + derived + " versions are derived from pom.xml, below the "
                        + MIN_DERIVED_VERSIONS + " expected. Either the derivation was unwound or "
                        + "this test's pattern stopped matching; both make the checks here "
                        + "meaningless.");
    }

    @Test
    @DisplayName("the coordinates are declared in pom.xml only")
    void coordinatesAreNotDeclaredTwice() {
        Path root = repoRoot();
        String gradleProperties = read(root.resolve("gradle.properties"));

        for (String key : List.of("version", "group")) {
            assertFalse(Pattern.compile("^\\s*" + key + "\\s*=", Pattern.MULTILINE)
                            .matcher(gradleProperties).find(),
                    "gradle.properties declares " + key + " again. It is read out of pom.xml by "
                            + "build.gradle.kts precisely so a release bump is one edit. A "
                            + "declaration here silently wins over the derived value, and the two "
                            + "builds can then publish different coordinates.");
        }

        assertTrue(read(root.resolve("build.gradle.kts")).contains("async-test-parent"),
                "build.gradle.kts no longer reads the reactor coordinates out of pom.xml, so "
                        + "nothing sets group and version for the Gradle publication.");
    }

    @Test
    @DisplayName("every published module describes itself identically in both builds")
    void artifactDescriptionsMatchAcrossBuilds() {
        Path root = repoRoot();
        for (String module : PUBLISHED_MODULES) {
            String fromPom = firstGroup(
                    Pattern.compile("<description>([^<]+)</description>"),
                    read(root.resolve(module + "/pom.xml")),
                    "<description> in " + module + "/pom.xml");
            String fromGradle = kotlinStringAfter(
                    read(root.resolve(module + "/build.gradle.kts")), "description =");

            assertEquals(normalise(fromPom), normalise(fromGradle),
                    module + "/pom.xml and " + module + "/build.gradle.kts publish different "
                            + "descriptions. Whichever build releases decides what Maven Central "
                            + "shows, so the two have to say the same thing.");
        }
    }

    @Test
    @DisplayName("the published description states the real detector count")
    void artifactDescriptionStatesTheRealDetectorCount() {
        Path root = repoRoot();
        int actual = DetectorType.values().length;
        String expected = actual + " problem detectors";

        for (String file : List.of("async-test-lib/pom.xml", "async-test-lib/build.gradle.kts")) {
            String text = read(root.resolve(file));
            assertTrue(text.contains(expected),
                    file + " does not describe the library as having " + expected + ". "
                            + "DetectorType has " + actual + " constants, and that description is "
                            + "what Maven Central shows to every consumer, so a stale number there "
                            + "is a wrong claim rather than a cosmetic one.");
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /**
     * Walks up from the working directory to the reactor root. Surefire runs with the module
     * directory as the working directory and Gradle does the same, so both builds land one
     * level down; the loop covers either without hard-coding a relative path.
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding both pom.xml and "
                        + "settings.gradle.kts) above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /** Parses the single {@code <properties>} block of the reactor pom into name to value. */
    private static Map<String, String> pomProperties(String pomXml) {
        int start = pomXml.indexOf("<properties>");
        int end = pomXml.indexOf("</properties>", start);
        assertTrue(start >= 0 && end > start, "pom.xml has no <properties> block to read");

        Map<String, String> props = new LinkedHashMap<>();
        Matcher m = POM_PROPERTY.matcher(pomXml.substring(start, end));
        while (m.find()) {
            props.put(m.group(1), m.group(2).trim());
        }
        assertFalse(props.isEmpty(), "pom.xml <properties> block parsed as empty");
        return props;
    }

    /**
     * Reads the Kotlin string expression that follows {@code marker}, joining a
     * {@code "a" + "b"} concatenation into one value. Stops at the first closing quote that is
     * not followed by a {@code +}.
     */
    private static String kotlinStringAfter(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Could not find '" + marker + "' in the Gradle script");

        List<String> parts = new ArrayList<>();
        int i = at + marker.length();
        while (true) {
            int open = source.indexOf('"', i);
            assertTrue(open >= 0, "Unterminated string after '" + marker + "'");
            int close = source.indexOf('"', open + 1);
            assertTrue(close >= 0, "Unterminated string after '" + marker + "'");
            parts.add(source.substring(open + 1, close));

            int j = close + 1;
            while (j < source.length() && Character.isWhitespace(source.charAt(j))) {
                j++;
            }
            if (j >= source.length() || source.charAt(j) != '+') {
                break;
            }
            i = j + 1;
        }
        return String.join("", parts);
    }

    private static String firstGroup(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "Could not find " + what);
        return m.group(1);
    }

    /** Collapses whitespace so a line break in one build file is not a difference. */
    private static String normalise(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
