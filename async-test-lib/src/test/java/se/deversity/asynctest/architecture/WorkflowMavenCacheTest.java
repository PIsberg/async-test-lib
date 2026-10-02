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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow job that restores setup-java's Maven cache also reads it.
 *
 * <p>{@code actions/setup-java} with {@code cache: maven} restores {@code ~/.m2/repository} and
 * saves it at the end of the job. Six jobs also ran every Maven step with
 * {@code -Dmaven.repo.local=.m2/repository}, a directory inside the workspace: the restore
 * succeeded and the build never looked at it, so every run downloaded every dependency from
 * Central. That is what let a change in how Central serves one 60 MB artifact turn E2E Tests red
 * on {@code main} for a week (#884), and what made a {@code 429 Too Many Requests} fail an
 * examples shard on a PR whose change it never reached (#887).
 *
 * <p>The redirect bought nothing back: each of those jobs installs the library from source before
 * anything resolves it, so a restored copy of the same version is overwritten, not used.
 */
class WorkflowMavenCacheTest {

    /** A job key directly under {@code jobs:}. */
    private static final Pattern JOB_KEY = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");

    @Test
    @DisplayName("#887: no job restores the Maven cache and then points Maven at another repository")
    void aJobThatRestoresTheMavenCacheReadsIt() {
        List<String> unread = new ArrayList<>();
        int caching = 0;
        for (Path file : yamlFiles(repoRoot().resolve(".github/workflows"))) {
            String relative = repoRoot().relativize(file).toString().replace('\\', '/');
            for (List<String> job : jobsOf(readLines(file))) {
                String body = String.join("\n", job);
                if (!body.contains("cache: maven")) {
                    continue;
                }
                caching++;
                if (body.contains("maven.repo.local")) {
                    unread.add(relative + ": " + job.get(0).strip());
                }
            }
        }
        assertTrue(unread.isEmpty(),
                "These jobs restore ~/.m2/repository through setup-java's cache: maven and then run "
                        + "Maven against another repository with -Dmaven.repo.local, so the cache is "
                        + "never read and every run downloads every dependency from Central (#887). "
                        + "Drop the redirect:\n  " + String.join("\n  ", unread));
        assertTrue(caching >= 5,
                "Matched " + caching + " jobs with cache: maven; the workflows have more than that. "
                        + "Fewer means the scan stopped matching, which is not a pass.");
    }

    /** The lines of each job under {@code jobs:}, its key line first. */
    private static List<List<String>> jobsOf(List<String> lines) {
        List<List<String>> jobs = new ArrayList<>();
        boolean inJobs = false;
        List<String> current = null;
        for (String line : lines) {
            if (line.startsWith("jobs:")) {
                inJobs = true;
                continue;
            }
            if (!inJobs) {
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            if (JOB_KEY.matcher(line).matches()) {
                current = new ArrayList<>();
                jobs.add(current);
            }
            if (current != null) {
                current.add(line);
            }
        }
        return jobs;
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
