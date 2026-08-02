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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JAR named in every current instruction for attaching the agent.
 *
 * <p><strong>The failure this prevents.</strong> {@code Premain-Class} and {@code Agent-Class} are
 * in {@code async-test-agent}'s manifest and nowhere else, so {@code -javaagent} has to name that
 * JAR. The instructions did not: the agent's own class javadoc, the {@code AgentOptions} examples,
 * the exception message the agent throws when self-attach fails, {@code docs/USAGE.md} and
 * {@code docs/architecture/contention-engine.md} all told the reader to attach
 * {@code async-test-lib.jar}. That JAR has no {@code Premain-Class}, so following any of them gets
 * "Failed to find Premain-Class manifest attribute" and no agent. Since the agent is the only
 * path that feeds detectors without hand-written hooks, that reads as "the library found nothing"
 * rather than "the flag was wrong".
 *
 * <p>The name moved to the agent module when the reactor was split, and these references were left
 * behind. Three mentions of the old flag survive on purpose and are excluded below:
 * {@code docs/analysis/modularization.md}, {@code docs/DISTRIBUTION.md} and
 * {@code docs/CHANGELOG.md}, which describe what changed and have to quote the old form to do it.
 */
class AgentAttachInstructionTest {

    /** Where a reader could plausibly copy an attach command from. */
    private static final List<String> INSTRUCTION_SOURCES = List.of(
            "async-test-agent/src/main/java",
            "async-test-lib/src/main/java",
            "docs",
            "README.md");

    /**
     * Files whose {@code -javaagent:async-test-lib} mentions are deliberate history rather than
     * instructions. Two describe the module split itself and the changelog records the flag being
     * corrected; all three have to quote the old form to say what changed. Nobody copies an attach
     * command out of a changelog entry, so excluding it costs nothing the rest of this test covers.
     */
    private static final List<String> HISTORICAL_MENTIONS = List.of(
            "docs/analysis/modularization.md",
            "docs/DISTRIBUTION.md",
            "docs/CHANGELOG.md");

    @Test
    @DisplayName("every attach instruction names the JAR that carries Premain-Class")
    void attachInstructionsNameTheAgentJar() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();

        for (String source : INSTRUCTION_SOURCES) {
            Path path = root.resolve(source);
            if (!Files.exists(path)) {
                continue;
            }
            for (Path file : textFilesUnder(path)) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (HISTORICAL_MENTIONS.contains(relative)) {
                    continue;
                }
                String text = read(file);
                if (text.contains("-javaagent:async-test-lib")) {
                    offenders.add(relative);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These name async-test-lib.jar in a -javaagent instruction: " + offenders
                        + ". Premain-Class is in async-test-agent's manifest only, so that flag "
                        + "fails to attach. Use -javaagent:async-test-agent-<version>.jar. If the "
                        + "mention is history rather than an instruction, such as a migration "
                        + "note or a changelog entry, add the file to HISTORICAL_MENTIONS.");
    }

    @Test
    @DisplayName("the module that carries Premain-Class is still async-test-agent")
    void premainClassStillLivesInTheAgentModule() {
        Path root = repoRoot();
        assertTrue(read(root.resolve("async-test-agent/pom.xml")).contains("<Premain-Class>"),
                "async-test-agent/pom.xml no longer declares Premain-Class. If the manifest moved, "
                        + "every attach instruction this test guards has to move with it.");
        assertTrue(read(root.resolve("async-test-agent/build.gradle.kts")).contains("Premain-Class"),
                "async-test-agent/build.gradle.kts no longer declares Premain-Class, so the Gradle "
                        + "build would publish an agent JAR that cannot be attached.");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static List<Path> textFilesUnder(Path start) {
        if (Files.isRegularFile(start)) {
            return List.of(start);
        }
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".md");
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + start, e);
        }
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
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
