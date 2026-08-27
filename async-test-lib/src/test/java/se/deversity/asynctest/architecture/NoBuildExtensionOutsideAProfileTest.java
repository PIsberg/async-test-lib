package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No build extension may sit in the root build, where every job has to resolve it.
 *
 * <p><strong>The failure this prevents, measured rather than imagined.</strong> A Maven build
 * extension is resolved while the project model is being assembled - before any goal runs, before
 * the transfer-retry settings {@code MavenTransferRetryTest} pins take effect, and before a single
 * test executes. {@code central-publishing-maven-plugin} sat in the root {@code <build><plugins>}
 * with {@code <extensions>true</extensions>}, so all 38 checks, on every OS and JDK, had to reach
 * Maven Central for a plugin whose only job is publishing to Maven Central. When that one fetch
 * failed the job died with {@code Unresolveable build extension} and
 * {@code ProjectBuildingException}, which no retry inside the build can recover from.
 *
 * <p>It happened three times in three days on three different runners: the Examples Reactor shard
 * 1/4 on 2026-08-25 (which #342 responded to with transfer retry),
 * {@code Test Suite (21, ubuntu-latest)} on #370, and {@code Test Suite (21, windows-latest)} on
 * #375. Each time the harden-runner log showed nothing blocked, so it was a genuine transient read
 * that happened to be fatal because of where the dependency sat. #342 made the fetch more likely
 * to survive; this makes 37 of the 38 jobs not need it at all. See issue #377.
 *
 * <p>The plugin now lives in the {@code release} profile, which {@code publish.yml} activates with
 * {@code mvn --batch-mode clean deploy -P release}. Verified from Maven's own debug output rather
 * than by reading the POM: {@code mvn -X -N validate} never mentions the plugin, and
 * {@code mvn -X -N validate -P release} logs
 * {@code Created new class realm extension>org.sonatype.central:central-publishing-maven-plugin}.
 */
class NoBuildExtensionOutsideAProfileTest {

    @Test
    @DisplayName("the root build declares no extension; anything that needs one lives in a profile")
    void noExtensionIsResolvedBeforeTheReactorIsRead() {
        String pom = read(repoRoot().resolve("pom.xml"));

        int profiles = pom.indexOf("<profiles>");
        assertTrue(profiles > 0,
                "the root pom has no <profiles> section any more, so this test cannot tell the "
                        + "root build from a profile and is checking nothing");

        String rootBuild = pom.substring(0, profiles);
        assertTrue(!rootBuild.contains("<extensions>true</extensions>"),
                "A build extension declared outside a profile is resolved when Maven reads the "
                        + "POM: before any goal, before transfer retry, before any test. Every job "
                        + "in CI then depends on that one fetch, and a transient failure kills the "
                        + "job with Unresolveable build extension, which nothing in the build can "
                        + "recover from. It has cost three CI jobs. Put the plugin in the profile "
                        + "that actually needs it. See issue #377.");
    }

    @Test
    @DisplayName("the release profile still carries the publishing plugin, as an extension")
    void theReleaseProfileStillPublishes() {
        String pom = read(repoRoot().resolve("pom.xml"));
        int profiles = pom.indexOf("<profiles>");
        String profileSection = pom.substring(Math.max(0, profiles));

        assertTrue(profileSection.contains("central-publishing-maven-plugin"),
                "moving the plugin out of the root build must not lose it: publish.yml runs "
                        + "mvn clean deploy -P release and needs it there. If this fails, "
                        + "releases stopped reaching Maven Central and nothing else would have "
                        + "said so until a tag was cut.");
        assertTrue(profileSection.contains("<extensions>true</extensions>"),
                "and it has to stay an extension, because that is what binds the deploy lifecycle "
                        + "to the Central Portal rather than to the default deploy plugin");
        assertTrue(profileSection.contains("<waitUntil>uploaded</waitUntil>"),
                "the waitUntil setting from #250 has to survive the move: waiting for 'published' "
                        + "blocks mvn deploy on Central's pipeline, which killed the publish job "
                        + "mid-poll for both 1.7.0 and 1.9.1 and lost every post-upload step");
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
