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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the transfer-retry settings that keep a single packet-level fault from reddening a CI job
 * before any code in this repository has run.
 *
 * <p><strong>The failure this prevents.</strong> On 2026-08-25 the Examples Reactor shard 1/4
 * failed with {@code Unresolveable build extension:
 * org.sonatype.central:central-publishing-maven-plugin:0.11.0 ... Failed to read artifact
 * descriptor}, while shards 0, 2 and 3 succeeded in the same run, at the same instant, against
 * the identical root pom. A build extension is resolved before the reactor is read, so nothing in
 * the build could have influenced it and nothing in the build could have recovered from it.
 *
 * <p><strong>What was actually missing.</strong> Not the retry count. Both transports Maven can
 * use already retry three times by default, verified from the artifacts rather than from the
 * documentation: {@code ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_COUNT} is {@code 3} in
 * {@code maven-resolver-api}, and {@code AbstractHttpClientWagon} initialises
 * {@code RETRY_HANDLER_COUNT} with {@code Integer.getInteger(key, 3)}. What both default to
 * <em>off</em> is {@code retryHandler.requestSentEnabled}, which governs a request whose bytes
 * already went out - the connection reset mid-response that this failure looks like. That flag,
 * on both transports, is the whole fix.
 *
 * <p><strong>Why both property families.</strong> Maven 3.9 resolves through
 * {@code maven-resolver-transport-http} and reads {@code aether.connector.http.*}; that jar
 * contains no reference to any {@code maven.wagon.*} key, and {@code maven-core} 3.9.9 maps none
 * across, so the wagon property alone would be a silent no-op on the version CI runs. Maven 3.8,
 * still in use locally, ships only the wagon transport and reads {@code maven.wagon.http.*}.
 * Setting both costs nothing: an unrecognised {@code -D} is an ordinary system property.
 *
 * <p><strong>Why a file and not a flag per job.</strong> This repository invokes Maven from more
 * than twenty steps across ten workflows, plus four separate reactors reached with {@code -f}.
 * A flag repeated in twenty places is a flag forgotten on the twenty-first, which is how the
 * corpus-eval pin drifted. {@code .mvn/maven.config} is read once per invocation from the
 * directory Maven walks up from, so every job inherits it - <em>as long as</em> Maven is launched
 * from the repository root. That last clause is a real precondition, so this test checks it.
 */
class MavenTransferRetryTest {

    /** The one setting that is off by default on both transports, per family. */
    private static final List<String> REQUIRED = List.of(
            "-Daether.connector.http.retryHandler.requestSentEnabled=true",
            "-Dmaven.wagon.http.retryHandler.requestSentEnabled=true");

    /** A step invoking Maven. Matches {@code mvn} and {@code ./mvnw} as a whole word. */
    private static final Pattern MAVEN_CALL =
            Pattern.compile("(^|[^\\w./])(\\./mvnw|mvnw|mvn)\\s");

    /** A list item in YAML: the boundary this test treats as the start of a step. */
    private static final Pattern STEP_START = Pattern.compile("^\\s*- \\S");

    private static final Pattern WORKING_DIRECTORY = Pattern.compile("^\\s*working-directory:");

    /** A directory change inside a run block, which would move Maven off the repository root. */
    private static final Pattern CHANGES_DIRECTORY =
            Pattern.compile("(^\\s*|[;&|]\\s*|run:\\s*)cd\\s+\\S");

    @Test
    @DisplayName("the retry setting both transports default to off is set once, in .mvn/maven.config")
    void bothTransportsRetryARequestWhoseBytesAlreadyWentOut() {
        Path config = repoRoot().resolve(".mvn/maven.config");
        assertTrue(Files.isRegularFile(config),
                "Expected " + config + ". Without it every Maven invocation in CI runs with "
                        + "requestSentEnabled=false, and a connection reset mid-response fails "
                        + "the job outright instead of being retried.");

        List<String> lines = readLines(config);
        for (String required : REQUIRED) {
            assertTrue(lines.stream().anyMatch(l -> l.strip().equals(required)),
                    "Missing from .mvn/maven.config: " + required
                            + "\nBoth families are needed: Maven 3.9 reads the aether key and "
                            + "ignores the wagon one, Maven 3.8 the reverse. Present:\n  "
                            + String.join("\n  ", lines));
        }
    }

    /**
     * Maven 3.8 parses {@code maven.config} as one argument per line and has no comment syntax:
     * a {@code #} line reaches the command-line parser and aborts the build. Verified by running
     * it, not by reading the documentation.
     */
    @Test
    @DisplayName(".mvn/maven.config holds only arguments: no comments, no blank lines, no CR")
    void theConfigContainsNothingMavenWouldRejectOrMisread() {
        Path config = repoRoot().resolve(".mvn/maven.config");
        String raw = readString(config);

        assertFalse(raw.contains("\r"),
                ".mvn/maven.config contains a carriage return. Maven splits the file by line and "
                        + "keeps the rest verbatim, so the value becomes \"true\\r\", which "
                        + "Boolean.getBoolean reads as false - the flags would be silently off on "
                        + "the Windows legs. .gitattributes pins these files to eol=lf; check that "
                        + "it still covers .mvn/*.config.");

        List<String> bad = readLines(config).stream()
                .filter(l -> l.isBlank() || !l.strip().startsWith("-"))
                .toList();
        assertTrue(bad.isEmpty(),
                "Every line of .mvn/maven.config must be a single Maven argument. Maven 3.8 has no "
                        + "comment syntax here and fails the build on anything it cannot parse. "
                        + "Offending lines:\n  " + String.join("\n  ", bad)
                        + "\nPut the reasoning in docs/BUILDING.md instead.");
    }

    /**
     * The precondition the file-based approach rests on. Maven finds {@code .mvn} by walking up
     * from the working directory, not from the {@code -f} argument, so {@code mvn -f
     * examples/pom.xml} launched from the root still picks the config up - but a step that first
     * moves elsewhere does not, and would lose the settings with nothing visible to show for it.
     */
    @Test
    @DisplayName("every workflow step invoking Maven runs from the repository root")
    void noMavenStepLeavesTheDirectoryTheConfigIsFoundFrom() {
        List<Path> files = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(files.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        for (Path file : files) {
            scanSteps(file, findings);
        }

        assertTrue(findings.isEmpty(),
                "These workflow steps invoke Maven from somewhere other than the repository root, "
                        + "so .mvn/maven.config does not apply to them and the transfer-retry "
                        + "setting is silently off:\n  " + String.join("\n  ", findings)
                        + "\nEither invoke Maven from the root with -f, or pass "
                        + String.join(" ", REQUIRED) + " on that command explicitly.");
    }

    /**
     * Splits a workflow into steps at each {@code - } list item and reports a step that both
     * invokes Maven and changes where it runs.
     */
    private static void scanSteps(Path file, List<String> findings) {
        List<String> lines = readLines(file);
        List<int[]> steps = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (STEP_START.matcher(lines.get(i)).find()) {
                steps.add(new int[]{start, i});
                start = i;
            }
        }
        steps.add(new int[]{start, lines.size()});

        String relative = repoRoot().relativize(file).toString().replace('\\', '/');
        for (int[] step : steps) {
            int mavenLine = -1;
            for (int i = step[0]; i < step[1]; i++) {
                if (MAVEN_CALL.matcher(lines.get(i)).find()) {
                    mavenLine = i;
                    break;
                }
            }
            if (mavenLine < 0) {
                continue;
            }
            for (int i = step[0]; i < step[1]; i++) {
                String line = lines.get(i);
                if (WORKING_DIRECTORY.matcher(line).find()
                        || CHANGES_DIRECTORY.matcher(line).find()) {
                    findings.add(relative + ":" + (i + 1) + "  " + line.strip()
                            + "   (Maven invoked at line " + (mavenLine + 1) + ")");
                }
            }
        }
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
        return readString(path).lines().toList();
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
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
