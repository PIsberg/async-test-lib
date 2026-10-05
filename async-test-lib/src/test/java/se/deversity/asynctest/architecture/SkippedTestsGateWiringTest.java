package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every CI job that runs a test suite checks its skipped tests against the committed baseline
 * (#905).
 *
 * <p>CI reports skips as a count nobody reads, and treats them as passes. The real-licence E2E
 * tests skipped on every leg for about two months while guarding the only real-grant path. The
 * gate is {@code .github/scripts/skipped_tests_gate.py}: after a job's tests it reads the JUnit
 * XML and fails when a skip is not in {@code .github/skipped-tests.txt}, or a baselined skip ran.
 * This test holds the wiring: a job listed in {@link #GATED} must call it, and any other job that
 * runs a suite must be listed there or in {@link #EXEMPT} with a reason, so a new workflow cannot
 * opt out by being forgotten.
 */
class SkippedTestsGateWiringTest {

    private static final String GATE = ".github/scripts/skipped_tests_gate.py";

    /** Workflow file to the job ids that must run the gate after their tests. */
    private static final Map<String, List<String>> GATED = Map.of(
            "tests.yml", List.of("test", "os-sensitive"),
            "corpus.yml", List.of("corpus-eval"),
            "e2e-tests.yml", List.of("consumer-fixture", "junit-compatibility"),
            "license-e2e.yml", List.of("license-e2e"),
            "gradle-tests.yml", List.of("gradle-test", "intellij-plugin"),
            "load-tests.yml", List.of("load-tests"));

    private static final String DEMOS = "its skips are the @Disabled example demonstrations, which"
            + " ExampleDisabledDemoTest and example-demos.yml already gate";

    /** {@code workflow:job} to why it runs tests without the gate. */
    private static final Map<String, String> EXEMPT = Map.of(
            "e2e-tests.yml:examples-changed", DEMOS,
            "e2e-tests.yml:examples-all", DEMOS,
            "e2e-tests.yml:examples-jdk25", DEMOS,
            "gradle-tests.yml:gradle-examples-pr", DEMOS,
            "gradle-tests.yml:gradle-examples-full", DEMOS,
            "example-demos.yml:enabled-demos", "runs the @Disabled demonstrations on purpose; a demo"
                    + " that passes is its finding, and it has its own baseline",
            "publish.yml:publish", "builds a release tag whose commit Tests & Build already gated");

    /** A build command that runs tests: a Maven phase at or past test, or a Gradle test task. */
    private static final Pattern RUNS_TESTS = Pattern.compile(
            "(mvn|gradlew)[^#]*[ ](test|install|verify|deploy|check)(?![-A-Za-z])");

    @Test
    @DisplayName("every gated job calls the skipped-tests gate")
    void gatedJobsCallTheGate() {
        List<String> missing = new ArrayList<>();
        GATED.forEach((workflow, jobs) -> {
            Map<String, String> bodies = jobs(workflow);
            for (String job : jobs) {
                String body = bodies.get(job);
                assertTrue(body != null, "no job '" + job + "' in " + workflow);
                if (!body.contains(GATE)) {
                    missing.add(workflow + ":" + job);
                }
            }
        });
        assertTrue(missing.isEmpty(), "these jobs run tests but never check their skips against"
                + " .github/skipped-tests.txt (#905): " + missing);
    }

    @Test
    @DisplayName("every job that runs a suite is gated or exempt with a reason")
    void noTestJobIsForgotten() {
        TreeSet<String> forgotten = new TreeSet<>();
        for (Path file : workflowFiles()) {
            String workflow = file.getFileName().toString();
            jobs(workflow).forEach((job, body) -> {
                String id = workflow + ":" + job;
                boolean gated = GATED.getOrDefault(workflow, List.of()).contains(job);
                if (runsTests(body) && !gated && !EXEMPT.containsKey(id)) {
                    forgotten.add(id);
                }
            });
        }
        assertTrue(forgotten.isEmpty(), "these jobs run a test suite, but are neither in GATED nor"
                + " in EXEMPT; call " + GATE + " after the tests, or exempt them with a reason: "
                + forgotten);
        for (String id : EXEMPT.keySet()) {
            String[] parts = id.split(":", 2);
            assertTrue(jobs(parts[0]).containsKey(parts[1]),
                    "EXEMPT names a job that no longer exists; drop the entry: " + id);
        }
    }

    @Test
    @DisplayName("the test-run detector recognises the commands it exists for")
    void theDetectorSeesTestCommands() {
        assertTrue(runsTests("        run: mvn clean install"));
        assertTrue(runsTests("        run: ./gradlew test jacocoTestReport"));
        assertTrue(runsTests("          mvn -B -pl async-test-lib -am test -P e2e"));
        assertFalse(runsTests("        run: mvn -DskipTests clean install"));
        assertFalse(runsTests("        run: ./gradlew publishToMavenLocal -x test"));
        assertFalse(runsTests("          mvn -B -pl async-test-lib -am test-compile org.pitest:x"));
        assertTrue(Files.isRegularFile(repoRoot().resolve(GATE)), GATE + " is missing");
        assertTrue(Files.isRegularFile(repoRoot().resolve(".github/skipped-tests.txt")),
                "the baseline .github/skipped-tests.txt is missing");
    }

    /** {@return whether a job body runs a test suite}; joins shell line continuations first. */
    private static boolean runsTests(String body) {
        String joined = body.replace(" \\\n", " ");
        for (String line : joined.split("\n", -1)) {
            String code = line.strip();
            if (code.startsWith("#") || code.contains("skipTests") || code.contains("-x test")) {
                continue;
            }
            if (RUNS_TESTS.matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    /** {@return job id to the job's text, in file order, for one workflow} */
    private static Map<String, String> jobs(String workflow) {
        List<String> lines = read(repoRoot().resolve(".github/workflows").resolve(workflow));
        Map<String, String> jobs = new LinkedHashMap<>();
        int start = lines.indexOf("jobs:");
        String current = null;
        StringBuilder body = new StringBuilder();
        for (int i = start + 1; start >= 0 && i < lines.size(); i++) {
            String line = lines.get(i);
            if (isJobHeader(line)) {
                if (current != null) {
                    jobs.put(current, body.toString());
                }
                current = line.strip().replace(":", "");
                body.setLength(0);
            }
            body.append(line).append('\n');
        }
        if (current != null) {
            jobs.put(current, body.toString());
        }
        return jobs;
    }

    private static boolean isJobHeader(String line) {
        return line.length() > 2 && line.startsWith("  ") && line.charAt(2) != ' '
                && line.charAt(2) != '#' && line.strip().endsWith(":");
    }

    private static List<Path> workflowFiles() {
        try (Stream<Path> files = Files.list(repoRoot().resolve(".github/workflows"))) {
            List<Path> yaml = files.filter(f -> f.toString().endsWith(".yml")).sorted().toList();
            assertFalse(yaml.isEmpty(), "no workflow files found; an empty scan is not a pass");
            return yaml;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
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
