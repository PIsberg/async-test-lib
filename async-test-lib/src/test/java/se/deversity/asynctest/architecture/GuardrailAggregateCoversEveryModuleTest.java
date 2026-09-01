package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the reactor-root guardrail aggregate still holds a region for every module.
 *
 * <p>The aggregate is assembled from the {@code .vibetags-mod-*} files in the reactor root, one
 * per module, and a region whose mod file is absent is dropped rather than preserved.
 * {@code async-test-lib} is the first module in {@code <modules>}, so a build that regenerated it
 * on a tree where the other two had not yet produced theirs wrote an aggregate holding only its
 * own block, silently deleting 93 lines of agent and analysis guardrails. That is #423, and the
 * fix was to stop ignoring the mod files so every checkout has all three.
 *
 * <p>This is the gate on that fix. It does not check the mod files, it checks the thing a reader
 * and an agent actually load: if a partial build's aggregate ever reaches a commit, the region it
 * dropped is missing here and this goes red. Invariant 7 says annotations change and the build
 * regenerates, which holds only while every build regenerates the same thing.
 */
class GuardrailAggregateCoversEveryModuleTest {

    /** The modules of the reactor, each of which contributes one region to the aggregate. */
    private static final List<String> MODULES =
            List.of("async-test-lib", "async-test-agent", "async-test-analysis");

    /** The generated files that carry the full reactor-root aggregate. */
    private static final List<String> AGGREGATES = List.of("CLAUDE.md", "GEMINI.md");

    @Test
    @DisplayName("every module has a region in every root aggregate")
    void everyModuleHasARegion() {
        Path root = repoRoot();

        for (String aggregate : AGGREGATES) {
            String text = read(root.resolve(aggregate));
            for (String module : MODULES) {
                assertTrue(text.contains("<!-- VIBETAGS-MODULE: " + module + " -->"),
                        aggregate + " has no region for " + module + ". The aggregate is built "
                                + "from the .vibetags-mod-* files present on disk, so this is "
                                + "what a partial build produces: regenerate from a full "
                                + "reactor build (mvn install -DskipTests) and commit that. "
                                + "See #423.");
                assertTrue(text.contains("<!-- VIBETAGS-MODULE-END: " + module + " -->"),
                        aggregate + " opens a region for " + module + " and never closes it, so "
                                + "the aggregate was truncated rather than regenerated.");
            }
        }
    }

    @Test
    @DisplayName("every module's mod file is present, which is what keeps the aggregate whole")
    void everyModuleHasItsModFile() {
        Path root = repoRoot();

        for (String module : MODULES) {
            Path mod = root.resolve(".vibetags-mod-" + module);
            assertTrue(Files.isRegularFile(mod),
                    mod.getFileName() + " is missing. These files are committed on purpose: the "
                            + "next build assembles the aggregate from whichever ones it finds, "
                            + "so a missing one deletes that module's guardrails from CLAUDE.md "
                            + "and GEMINI.md without failing anything. See #423 and the note in "
                            + ".gitignore.");
        }
    }

    /** {@return the reactor root, the directory holding both builds' entry points} */
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
}
