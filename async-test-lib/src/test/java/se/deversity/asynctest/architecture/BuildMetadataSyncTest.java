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
 * <p><strong>The failure this prevents.</strong> Maven is the canonical build; Gradle is the
 * secondary developer build. Every shared version is therefore written twice, and until now the
 * only thing keeping the two copies equal was a comment asking people to remember. That has
 * already failed three times: the comment above the version block in {@code build.gradle.kts}
 * records that spotbugs, error-prone and pmd each drifted between the builds. Drift there is
 * quiet — both builds still pass, they just no longer run the same analyser — which is exactly
 * the kind of divergence a gate is for.
 *
 * <p>The published artifact description drifted the same way and stayed wrong: it claimed
 * "121 problem detectors" long after {@link DetectorType} had grown past that. See
 * {@link #artifactDescriptionStatesTheRealDetectorCount()}, which derives the number from the
 * enum rather than restating it. That string is what Maven Central shows, so a stale number
 * there is a wrong claim made to every consumer.
 *
 * <p><strong>How the version mapping is declared.</strong> Each version in
 * {@code build.gradle.kts} names the pom property it tracks in a trailing comment:
 *
 * <pre>{@code extra["asmVersion"] = "9.10.1"        // pom: asm.version}</pre>
 *
 * <p>That comment is the contract this test reads. A Gradle version with no {@code // pom:}
 * comment is deliberately unpinned (logback, for instance, is a test-only backend with no Maven
 * twin) and is skipped. A comment naming a property the pom does not define fails the test, so
 * renaming a pom property cannot silently orphan its Gradle copy.
 */
class BuildMetadataSyncTest {

    /** The three published modules, each with a pom and a Gradle script that must agree. */
    private static final List<String> PUBLISHED_MODULES =
            List.of("async-test-lib", "async-test-agent", "async-test-analysis");

    /**
     * Lower bound on how many {@code // pom:}-mapped versions must be found. Without it a
     * regex that stopped matching would make every assertion below pass vacuously — a green
     * run proving only that the parser found nothing.
     */
    private static final int MIN_MAPPED_VERSIONS = 15;

    private static final Pattern GRADLE_VERSION_LINE = Pattern.compile(
            "^\\s*extra\\[\"([^\"]+)\"\\]\\s*=\\s*\"([^\"]+)\"\\s*//\\s*pom:\\s*(\\S+)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern POM_PROPERTY = Pattern.compile(
            "<([A-Za-z0-9._-]+)>([^<>]*)</\\1>");

    @Test
    @DisplayName("every Gradle version that names a pom property matches that property")
    void gradleVersionsMatchTheirPomProperties() {
        Path root = repoRoot();
        Map<String, String> pomProps = pomProperties(read(root.resolve("pom.xml")));
        String gradle = read(root.resolve("build.gradle.kts"));

        Matcher m = GRADLE_VERSION_LINE.matcher(gradle);
        int mapped = 0;
        while (m.find()) {
            String gradleKey = m.group(1);
            String gradleValue = m.group(2);
            String pomProperty = m.group(3);
            mapped++;

            assertTrue(pomProps.containsKey(pomProperty),
                    "build.gradle.kts extra[\"" + gradleKey + "\"] tracks pom property <"
                            + pomProperty + ">, which pom.xml does not define. Either the property "
                            + "was renamed in pom.xml without updating the // pom: comment, or the "
                            + "comment is a typo.");

            assertEquals(pomProps.get(pomProperty), gradleValue,
                    "Maven and Gradle disagree on " + pomProperty + ". pom.xml says "
                            + pomProps.get(pomProperty) + ", build.gradle.kts extra[\"" + gradleKey
                            + "\"] says " + gradleValue + ". Maven is canonical — update Gradle.");
        }

        assertTrue(mapped >= MIN_MAPPED_VERSIONS,
                "Only " + mapped + " version mappings were parsed out of build.gradle.kts, below the "
                        + MIN_MAPPED_VERSIONS + " expected. The // pom: comment format most likely "
                        + "changed, which would make this gate pass without checking anything.");
    }

    @Test
    @DisplayName("the project version is the same in pom.xml and gradle.properties")
    void projectVersionMatchesAcrossBuilds() {
        Path root = repoRoot();
        String pomVersion = firstGroup(
                Pattern.compile("<artifactId>async-test-parent</artifactId>\\s*<version>([^<]+)</version>"),
                read(root.resolve("pom.xml")),
                "reactor parent <version> in pom.xml");
        String gradleVersion = firstGroup(
                Pattern.compile("^version=(.+)$", Pattern.MULTILINE),
                read(root.resolve("gradle.properties")),
                "version= in gradle.properties");

        assertEquals(pomVersion, gradleVersion.trim(),
                "pom.xml and gradle.properties disagree on the project version. A release cut from "
                        + "the wrong one publishes coordinates nobody expects.");
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
                            + "what Maven Central shows to every consumer — a stale number there is "
                            + "a wrong claim, not a cosmetic one.");
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
