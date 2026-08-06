package se.deversity.asynctest.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.E2E;

/**
 * Pins the {@code @E2E} tag set to the classes that actually drive the whole engine.
 *
 * <p>The local default build excludes {@code @Tag("e2e")} classes (the
 * {@code surefire.excludedGroups} property in the root pom), so an EngineTestKit meta-test
 * that lands without the tag silently moves its cost back into every local run, and a
 * mistyped tag silently drops a class out of the local tier without adding it to anything.
 * This test fails the build in both drift directions for the lib module and pins the two
 * agent-module end-to-end classes by path.
 */
class E2eTagGuardTest {

    /** Split so this file does not match its own EngineTestKit-import scan. */
    private static final String TESTKIT_PACKAGE = "org.junit.platform" + ".testkit";

    @Test
    void e2eTagIdMatchesTheMavenExclusion() {
        Tag tag = E2E.class.getAnnotation(Tag.class);
        assertEquals("e2e", tag.value(), "@E2E must carry the tag id the build excludes");
    }

    @Test
    void everyEngineTestKitClassCarriesTheTag() throws IOException {
        Path testRoot = repoRoot().resolve(Path.of("async-test-lib", "src", "test", "java"));
        List<Path> untagged;
        try (Stream<Path> files = Files.walk(testRoot)) {
            untagged = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> contentOf(p).contains(TESTKIT_PACKAGE))
                    .filter(p -> !contentOf(p).contains("@E2E"))
                    .toList();
        }
        assertTrue(untagged.isEmpty(),
                "EngineTestKit classes drive the full engine; tag them @E2E: " + untagged);
    }

    @Test
    void agentEndToEndClassesCarryTheTag() {
        for (String relative : List.of(
                "async-test-agent/src/test/java/se/deversity/asynctest/agent/SelfAttachTest.java",
                "async-test-agent/src/test/java/se/deversity/asynctest/agent/"
                        + "AgentFeedsDetectorEndToEndTest.java")) {
            Path file = repoRoot().resolve(relative);
            assertTrue(Files.exists(file),
                    "agent e2e class moved or renamed; update this guard: " + file);
            assertTrue(contentOf(file).contains("@Tag(\"e2e\")"),
                    "agent end-to-end class must carry @Tag(\"e2e\"): " + file);
        }
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve("pom.xml"))
                    && Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repo root not found above " + Path.of("").toAbsolutePath());
    }
}
