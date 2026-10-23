package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code versions/pom.xml} as the one place the builds outside the reactor take their
 * versions from.
 *
 * <p>The examples, both consumer fixtures, the corpus, the load tests and the README demo each used to restate
 * every version they used: 150 example poms and 150 example Gradle files carried the same JUnit
 * and library pins, and the language fixtures' Gradle twins sat on Groovy 5.1.0, Scala 3.8.4 and
 * Kotlin 2.4.10 while their Maven poms had moved to 6.0.0, 3.10.0 and 2.4.21. Now every satellite
 * pom inherits {@code versions/pom.xml}, and every satellite Gradle build reads the same file
 * through {@code versions/versions.settings.gradle.kts}. This test checks that the single source
 * holds: that no satellite build names a version of its own again, that every Gradle build is
 * wired to the shared file, and that the values the satellites share with the reactor agree with
 * it.
 */
class SatelliteVersionsTest {

    /** The builds outside the reactor. Each takes its versions from {@code versions/pom.xml}. */
    private static final List<String> SATELLITES =
            List.of("consumer-fixture", "consumer-fixture-langs", "corpus-eval", "examples", "load-tests", "tools/demo",
                    "async-test-maven-plugin", "async-test-gradle-plugin");

    /** Directories holding sources or build output rather than build definitions. */
    private static final Set<String> NOT_BUILD_DIRS = Set.of("src", "target", "build", ".gradle", "results");

    /**
     * Gradle plugins with no Maven twin, so there is no pom property to read them from, with the
     * reason. The gradle Dependabot ecosystem watches them where they are.
     */
    private static final Map<String, String> GRADLE_ONLY_PLUGINS = Map.of(
            "me.champeau.jmh", "load-tests is Gradle-only and JMH's Gradle plugin has no Maven counterpart",
            "com.gradle.plugin-publish", "publishes async-test-gradle-plugin to the Gradle Plugin Portal (#1099); "
                    + "a Gradle plugin, which no pom declares");

    /**
     * Floors on how much each check saw. A walk that stopped finding the examples would otherwise
     * leave every assertion passing while checking nothing.
     */
    private static final int MIN_SATELLITE_POMS = 150;
    private static final int MIN_SATELLITE_GRADLE_BUILDS = 150;
    private static final int MIN_SHARED_PROPERTIES = 5;

    private static final Pattern POM_PROPERTY = Pattern.compile("<([A-Za-z0-9._-]+)>([^<>]*)</\\1>");
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_OR_PLUGIN =
            Pattern.compile("<(dependency|plugin)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern LITERAL_VERSION = Pattern.compile("<version>\\s*(\\d[^<]*)</version>");
    private static final Pattern REACTOR_VERSION =
            Pattern.compile("<artifactId>async-test-parent</artifactId>\\s*<version>([^<]+)</version>");

    /** A {@code "group:artifact:1.2.3"} coordinate, or one with a version template after the colon. */
    private static final Pattern GRADLE_PINNED_COORDINATE =
            Pattern.compile("\"[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:\\d[A-Za-z0-9._-]*\"");
    /** A {@code val junitVersion = "6.1.3"} literal. */
    private static final Pattern GRADLE_VERSION_VAL =
            Pattern.compile("val\\s+\\w*[Vv]ersion\\s*(?::\\s*String)?\\s*=\\s*\"\\d");
    /** A plugin pinned in a {@code plugins {}} block: {@code id("x") version "1.2"} or {@code kotlin("jvm") version "2.4"}. */
    private static final Pattern GRADLE_PLUGIN_VERSION =
            Pattern.compile("(?:id|kotlin)\\(\"([^\"]+)\"\\)\\s*version\\s*\"\\d");

    @Test
    void sharedPropertiesAgreeWithTheReactor() {
        Map<String, String> reactor = properties(read(repoRoot().resolve("pom.xml")));
        Map<String, String> satellites = properties(read(repoRoot().resolve("versions/pom.xml")));

        List<String> drift = new ArrayList<>();
        int shared = 0;
        for (Map.Entry<String, String> e : satellites.entrySet()) {
            String reactorValue = reactor.get(e.getKey());
            if (reactorValue == null) {
                continue;
            }
            shared++;
            if (!reactorValue.equals(e.getValue())) {
                drift.add(e.getKey() + ": versions/pom.xml says " + e.getValue() + ", pom.xml says " + reactorValue);
            }
        }
        assertTrue(shared >= MIN_SHARED_PROPERTIES,
                "Only " + shared + " properties are shared between pom.xml and versions/pom.xml, expected at least "
                        + MIN_SHARED_PROPERTIES + " (JUnit Jupiter and Platform, Surefire, the compiler plugin, slf4j). "
                        + "If a property was renamed in one pom, rename it in the other.");
        assertTrue(drift.isEmpty(),
                "versions/pom.xml and pom.xml disagree. The satellites would build against a different version than "
                        + "the reactor; bump both together (Dependabot does, through group-by: dependency-name):\n  "
                        + String.join("\n  ", drift));
    }

    @Test
    void libraryUnderTestIsTheReactorVersion() {
        String reactorVersion = firstGroup(REACTOR_VERSION, read(repoRoot().resolve("pom.xml")));
        String pinned = properties(read(repoRoot().resolve("versions/pom.xml"))).get("async-test.version");
        assertEquals(reactorVersion, pinned,
                "versions/pom.xml <async-test.version> must equal the reactor version. CI publishes the working tree "
                        + "to the local repository as " + reactorVersion + "; a satellite pinned elsewhere resolves "
                        + "another release from Central and tests that instead. The release skill bumps both.");
    }

    @Test
    void noSatelliteMavenBuildPinsAVersion() {
        List<Path> poms = satelliteFiles("pom.xml");
        assertTrue(poms.size() >= MIN_SATELLITE_POMS,
                "Found " + poms.size() + " satellite poms, expected at least " + MIN_SATELLITE_POMS);

        List<String> pinned = new ArrayList<>();
        for (Path pom : poms) {
            String xml = read(pom);
            String rel = repoRoot().relativize(pom).toString().replace('\\', '/');
            if (!PARENT_BLOCK.matcher(xml).find()) {
                pinned.add(rel + ": no <parent>, so it cannot inherit versions/pom.xml");
            }
            String own = PARENT_BLOCK.matcher(xml).replaceFirst("");
            int start = own.indexOf("<properties>");
            if (start >= 0) {
                Matcher prop = POM_PROPERTY.matcher(own.substring(start, own.indexOf("</properties>", start)));
                while (prop.find()) {
                    if (prop.group(1).endsWith(".version")) {
                        pinned.add(rel + ": <" + prop.group(1) + ">" + prop.group(2) + "</" + prop.group(1) + ">");
                    }
                }
            }
            Matcher block = DEPENDENCY_OR_PLUGIN.matcher(own);
            while (block.find()) {
                Matcher literal = LITERAL_VERSION.matcher(block.group(2));
                if (literal.find()) {
                    pinned.add(rel + ": <" + block.group(1) + "> pinned at " + literal.group(1));
                }
            }
        }
        assertTrue(pinned.isEmpty(),
                "A satellite pom names a version of its own. Declare it once in versions/pom.xml and refer to it as "
                        + "${name.version}:\n  " + String.join("\n  ", pinned));
    }

    @Test
    void noSatelliteGradleBuildPinsAVersion() {
        List<Path> scripts = satelliteFiles("build.gradle.kts");
        assertTrue(scripts.size() >= MIN_SATELLITE_GRADLE_BUILDS,
                "Found " + scripts.size() + " satellite Gradle builds, expected at least " + MIN_SATELLITE_GRADLE_BUILDS);

        List<String> pinned = new ArrayList<>();
        for (Path script : scripts) {
            String kts = read(script);
            String rel = repoRoot().relativize(script).toString().replace('\\', '/');
            for (Pattern p : List.of(GRADLE_PINNED_COORDINATE, GRADLE_VERSION_VAL)) {
                Matcher m = p.matcher(kts);
                while (m.find()) {
                    pinned.add(rel + ": " + m.group());
                }
            }
            Matcher plugin = GRADLE_PLUGIN_VERSION.matcher(kts);
            while (plugin.find()) {
                if (!GRADLE_ONLY_PLUGINS.containsKey(plugin.group(1))) {
                    pinned.add(rel + ": " + plugin.group());
                }
            }
        }
        assertTrue(pinned.isEmpty(),
                "A satellite Gradle build names a version of its own. Read it from versions/pom.xml as "
                        + "libs.versions.<name>.version, or the plugin through libs.plugins:\n  "
                        + String.join("\n  ", pinned));
    }

    @Test
    void everySatelliteGradleBuildReadsTheVersionsPom() {
        List<Path> settings = satelliteFiles("settings.gradle.kts");
        assertTrue(settings.size() >= MIN_SATELLITE_GRADLE_BUILDS,
                "Found " + settings.size() + " satellite settings files, expected at least " + MIN_SATELLITE_GRADLE_BUILDS);

        List<String> unwired = settings.stream()
                .filter(s -> !read(s).contains("versions/versions.settings.gradle.kts"))
                .map(s -> repoRoot().relativize(s).toString().replace('\\', '/'))
                .toList();
        assertTrue(unwired.isEmpty(),
                "These settings files do not apply versions/versions.settings.gradle.kts, so libs.versions.* does "
                        + "not exist when the build is run from that directory:\n  " + String.join("\n  ", unwired));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static List<Path> satelliteFiles(String name) {
        List<Path> found = new ArrayList<>();
        for (String satellite : SATELLITES) {
            try (Stream<Path> walk = Files.walk(repoRoot().resolve(satellite))) {
                walk.filter(p -> p.getFileName().toString().equals(name))
                        .filter(p -> {
                            for (Path part : repoRoot().relativize(p)) {
                                if (NOT_BUILD_DIRS.contains(part.toString())) {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .forEach(found::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not walk " + satellite, e);
            }
        }
        return found;
    }

    /** The first {@code <properties>} block of a pom, name to value. */
    private static Map<String, String> properties(String pomXml) {
        int start = pomXml.indexOf("<properties>");
        int end = pomXml.indexOf("</properties>", start);
        assertTrue(start >= 0 && end > start, "pom has no <properties> block to read");
        Map<String, String> props = new LinkedHashMap<>();
        Matcher m = POM_PROPERTY.matcher(pomXml.substring(start, end));
        while (m.find()) {
            props.put(m.group(1), m.group(2).trim());
        }
        return props;
    }

    /** Walks up from the working directory to the reactor root; see BuildMetadataSyncTest. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
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

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "Could not find " + pattern);
        return m.group(1);
    }
}
