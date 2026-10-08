package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Maven build in the repository is watched by a Dependabot {@code maven} entry (#958).
 *
 * <p>Dependabot reads a reactor's modules from the directory it is pointed at and nothing else.
 * Its only Maven entry pointed at the root, so {@code corpus-eval}, a standalone build, held a
 * jackson-databind with two high advisories for a week and no PR was ever raised. A top-level
 * directory with its own {@code pom.xml} that is not a module of the root reactor must be listed
 * under a {@code maven} entry's {@code directory} or {@code directories}.
 */
class DependabotWatchesEveryMavenBuildTest {

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern DIRECTORY = Pattern.compile("^\\s*(?:directory:|-)\\s*(/[^\\s#]*)");

    @Test
    @DisplayName("every standalone Maven build is in a Dependabot maven entry")
    void everyStandaloneBuildIsWatched() {
        Path root = repoRoot();
        Set<String> modules = new HashSet<>();
        Matcher m = MODULE.matcher(read(root.resolve("pom.xml")));
        while (m.find()) {
            modules.add(m.group(1).strip());
        }
        assertFalse(modules.isEmpty(), "no <module> in the root pom; an empty reactor is not a pass");

        Set<String> watched = mavenDirectories(read(root.resolve(".github/dependabot.yml")));
        assertTrue(watched.contains("/"), "no Dependabot maven entry for the reactor root");

        TreeSet<String> unwatched = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (Files.isRegularFile(dir.resolve("pom.xml")) && !modules.contains(name)
                        && !watched.contains("/" + name)) {
                    unwatched.add(name);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(unwatched.isEmpty(), "these Maven builds are not reactor modules and no"
                + " Dependabot maven entry lists them, so nothing proposes their security fixes"
                + " (#958); add them to .github/dependabot.yml: " + unwatched);
    }

    /** {@return the directories of every {@code maven} entry, read line by line} */
    private static Set<String> mavenDirectories(String yaml) {
        Set<String> dirs = new HashSet<>();
        boolean maven = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.strip().startsWith("- package-ecosystem:")) {
                maven = line.contains("maven");
                continue;
            }
            Matcher d = DIRECTORY.matcher(line);
            if (maven && d.find()) {
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
