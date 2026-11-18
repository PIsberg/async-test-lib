package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every job in {@code e2e-tests.yml} that builds the library caches its own Maven dependencies
 * (#890, #1405).
 *
 * <p>{@code actions/setup-java}'s {@code cache: maven} is one entry shared by every job, keyed on
 * the pom hashes and never saved again after a hit, so a job whose plugins are not in it fetches
 * them from Maven Central on every run. #890 gave the consumer-fixture and examples jobs keys of
 * their own; the JUnit compatibility job kept the shared one, and under load Central answered it
 * with 429 Too Many Requests, failing two runs in a row (#1405).
 */
class E2eJobsCacheTheirOwnDependenciesTest {

    private static final String WORKFLOW = ".github/workflows/e2e-tests.yml";

    /** The setup-java input itself, not a comment that mentions it. */
    private static final Pattern SHARED_CACHE = Pattern.compile("(?m)^\\s+cache:\\s*maven\\s*$");

    /** A job header: two spaces of indentation, a name, a colon, nothing else. */
    private static final Pattern JOB = Pattern.compile("(?m)^  ([A-Za-z0-9_-]+):\\s*$");

    @Test
    void noJobUsesTheSharedSetupJavaCache() {
        List<String> shared = new ArrayList<>();
        for (Job job : jobs()) {
            if (SHARED_CACHE.matcher(job.body).find()) {
                shared.add(job.name);
            }
        }
        assertEquals(List.of(), shared, "these jobs use setup-java's shared cache: maven instead of "
                + "an actions/cache entry of their own (#890, #1405)");
    }

    @Test
    void everyJobThatInstallsTheLibraryCachesItsMavenRepository() {
        List<String> uncached = new ArrayList<>();
        for (Job job : jobs()) {
            if (job.body.contains("mvn -DskipTests clean install") && !job.body.contains("~/.m2/repository")) {
                uncached.add(job.name);
            }
        }
        assertEquals(List.of(), uncached, "these jobs build the library with no cache of their own, "
                + "so every plugin comes from Maven Central on every run (#1405)");
    }

    private record Job(String name, String body) {
    }

    /** {@return the workflow's jobs, each with the text up to the next job} */
    private static List<Job> jobs() {
        String workflow = read();
        int jobsAt = workflow.indexOf("\njobs:");
        Matcher header = JOB.matcher(workflow);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int from = jobsAt;
        while (header.find(from)) {
            names.add(header.group(1));
            spans.add(new int[] {header.start(), 0});
            from = header.end();
        }
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : workflow.length();
            jobs.add(new Job(names.get(i), workflow.substring(spans.get(i)[0], end)));
        }
        return jobs;
    }

    private static String read() {
        Path path = repoRoot().resolve(WORKFLOW);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve(".github"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no .github directory above " + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
