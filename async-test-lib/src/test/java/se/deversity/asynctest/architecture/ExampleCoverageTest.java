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
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completeness gate for {@code examples/}.
 *
 * <p>{@code DetectorCoverageTest} in {@code consumer-fixture} already pins the other half of
 * this: every {@code DetectorType} must have exactly one fixture method. Nothing pinned the
 * examples, and they had drifted — six detectors shipped with no example at all, and one
 * example was missing from the index table. Both are the kind of gap that is invisible until
 * somebody goes looking, which is what a gate is for.
 *
 * <p>What this test guarantees, precisely: every {@code *Detector} and {@code *Monitor} class
 * the library ships is named in the <em>source</em> of at least one example, and the three
 * places an example has to be registered agree with the directories on disk.
 *
 * <p>The scan covers {@code .java} and {@code .kt} only, deliberately. An earlier draft
 * included {@code .md} and was too weak to be worth having: a row in the index table names the
 * detector class, so the gate stayed green for a detector whose example directory had been
 * deleted. Requiring the name in code means something has to actually reference the class.
 *
 * <p>What it still does not guarantee: that the example is any good. A detector named in an
 * unused import satisfies this gate. That is a weaker promise than the consumer fixture's,
 * which reflects over real {@code @AsyncTest} annotations, and it is the honest limit of a
 * text scan. Treat a pass as "nobody forgot the example", not "the example demonstrates the
 * detector".
 */
@DisplayName("examples/ covers every detector and stays registered in both builds")
class ExampleCoverageTest {

    /** Directories under {@code examples/} that are build output rather than an example. */
    private static final Set<String> NOT_AN_EXAMPLE = Set.of("build", "target");

    @Test
    @DisplayName("every shipped Detector and Monitor is named by at least one example")
    void everyDetectionComponentHasAnExample() {
        Path root = repoRoot();
        Set<String> components = detectionComponents(root);
        assertTrue(components.size() > 100,
                "Expected to find the detector set; found only " + components.size()
                        + " — the scan is probably looking in the wrong place");

        String corpus = exampleCorpus(root);
        List<String> missing = components.stream()
                .filter(c -> !namedIn(corpus, c))
                .toList();

        assertTrue(missing.isEmpty(),
                "Every detector shipped by the library needs an example under examples/. "
                        + "Missing: " + missing
                        + ". Add one modelled on examples/129-confined-arena-thread-escape, "
                        + "register it in examples/pom.xml and examples/settings.gradle.kts, "
                        + "and add its row to examples/README.md.");
    }

    @Test
    @DisplayName("Maven modules, Gradle includes and the directories on disk agree")
    void everyExampleIsRegisteredInBothBuilds() {
        Path root = repoRoot();
        Set<String> dirs = exampleDirs(root);
        Set<String> modules = matches(read(root.resolve("examples/pom.xml")),
                "<module>([^<]+)</module>");
        Set<String> includes = matches(read(root.resolve("examples/settings.gradle.kts")),
                "include\\(\":([^\"]+)\"\\)");

        // assertTrue on the differences rather than assertEquals on the sets: with 130-odd
        // examples, an assertEquals failure prints both lists in full and buries the one
        // entry that actually differs.
        assertTrue(minus(dirs, modules).isEmpty() && minus(modules, dirs).isEmpty(),
                "examples/pom.xml <modules> must list exactly the example directories. "
                        + "Missing from the pom: " + minus(dirs, modules)
                        + ". Listed but not on disk: " + minus(modules, dirs));
        assertTrue(minus(dirs, includes).isEmpty() && minus(includes, dirs).isEmpty(),
                "examples/settings.gradle.kts include(...) must list exactly the example "
                        + "directories. Missing from settings: " + minus(dirs, includes)
                        + ". Listed but not on disk: " + minus(includes, dirs));
    }

    @Test
    @DisplayName("every example has a row in the examples/README.md index")
    void everyExampleIsInTheIndexTable() {
        Path root = repoRoot();
        Set<String> dirs = exampleDirs(root);
        String readme = read(root.resolve("examples/README.md"));
        Set<String> linked = matches(readme, "\\]\\((\\d[^)/]*)/\\)");

        List<String> missing = dirs.stream().filter(d -> !linked.contains(d)).toList();
        assertTrue(missing.isEmpty(),
                "The index table in examples/README.md is how anyone finds an example, so an "
                        + "unlisted one may as well not exist. Missing rows for: " + missing);
    }

    // ------------------------------------------------------------------ helpers

    /** Every {@code *Detector} / {@code *Monitor} class in the library's main sources. */
    private static Set<String> detectionComponents(Path root) {
        Path main = root.resolve("async-test-lib/src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            Set<String> names = new TreeSet<>();
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Detector.java") || n.endsWith("Monitor.java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    // The SPI interface and its adapter are contracts, not detectors.
                    .filter(n -> !n.equals("Detector") && !n.equals("LegacyDetector"))
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + main, e);
        }
    }

    private static Set<String> exampleDirs(Path root) {
        Path examples = root.resolve("examples");
        try (Stream<Path> entries = Files.list(examples)) {
            Set<String> dirs = new TreeSet<>();
            entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .filter(n -> !NOT_AN_EXAMPLE.contains(n))
                    .forEach(dirs::add);
            return dirs;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + examples, e);
        }
    }

    /**
     * All {@code .java} and {@code .kt} source under {@code examples/}, concatenated.
     *
     * <p>Markdown is excluded on purpose — see the class javadoc. Including it made the gate
     * satisfiable by an index-table row alone.
     */
    private static String exampleCorpus(Path root) {
        Path examples = root.resolve("examples");
        StringBuilder sb = new StringBuilder(1 << 20);
        try (Stream<Path> files = Files.walk(examples)) {
            files.filter(Files::isRegularFile)
                    .filter(ExampleCoverageTest::isSource)
                    .filter(p -> !isBuildOutput(examples, p))
                    .forEach(p -> sb.append(read(p)).append('\n'));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + examples, e);
        }
        return sb.toString();
    }

    private static boolean isSource(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".java") || n.endsWith(".kt");
    }

    private static boolean isBuildOutput(Path examples, Path file) {
        for (Path part : examples.relativize(file)) {
            String n = part.toString();
            if (NOT_AN_EXAMPLE.contains(n) || n.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** Word-boundary match, so {@code SharedRandomDetector} does not satisfy a longer sibling. */
    private static boolean namedIn(String corpus, String className) {
        int from = 0;
        while (true) {
            int i = corpus.indexOf(className, from);
            if (i < 0) {
                return false;
            }
            int after = i + className.length();
            boolean trailingIdent = after < corpus.length()
                    && (Character.isLetterOrDigit(corpus.charAt(after)) || corpus.charAt(after) == '_');
            if (!trailingIdent) {
                return true;
            }
            from = after;
        }
    }

    private static Set<String> matches(String text, String regex) {
        Set<String> found = new TreeSet<>();
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        List<String> only = new ArrayList<>(a);
        only.removeAll(b);
        return only;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("examples"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding pom.xml, "
                        + "settings.gradle.kts and examples/) above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
