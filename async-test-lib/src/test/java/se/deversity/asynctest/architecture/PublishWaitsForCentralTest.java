package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish Release is green only once Maven Central serves the jars it built (#952).
 *
 * <p>The deploy uses {@code waitUntil=uploaded}, so a green run used to mean only "the bundle was
 * uploaded": v1.13.0's went green while nothing reached Central (#949). The last step of
 * {@code publish.yml} runs {@code .github/scripts/wait-for-central.sh}, which compares each
 * module's sha1 on Central with the jar the run built and fails after a bound. The script's own
 * behaviour was checked against 1.12.4 (present), a tampered jar and 9.9.9 (absent).
 */
class PublishWaitsForCentralTest {

    private static final String SCRIPT = ".github/scripts/wait-for-central.sh";

    @Test
    @DisplayName("publish.yml waits for Central after the release, with a bound")
    void publishWaitsForCentral() throws IOException {
        Path root = repoRoot();
        assertTrue(Files.isRegularFile(root.resolve(SCRIPT)), SCRIPT + " is missing");
        String publish = Files.readString(root.resolve(".github/workflows/publish.yml"),
                StandardCharsets.UTF_8);
        int deploy = publish.indexOf("clean deploy");
        int wait = publish.indexOf(SCRIPT);
        assertTrue(deploy >= 0, "publish.yml no longer deploys; this test reads the wrong file");
        assertTrue(wait > deploy, "publish.yml must run " + SCRIPT + " after the deploy, or a green"
                + " run says only that the bundle was uploaded (#952)");
        int stepStart = publish.lastIndexOf("- name:", wait);
        assertTrue(publish.substring(stepStart, wait).contains("timeout-minutes:"),
                "the wait step needs its own timeout-minutes, so a stalled poll cannot outlive the"
                        + " script's bound");
    }

    @Test
    @DisplayName("publish.yml creates the GitHub Release only once Central serves the jars")
    void githubReleaseWaitsForCentral() throws IOException {
        String publish = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"),
                StandardCharsets.UTF_8);
        int wait = publish.indexOf(SCRIPT);
        int release = publish.indexOf("gh release create");
        assertTrue(release >= 0, "publish.yml no longer creates a GitHub Release; this test reads"
                + " the wrong file");
        assertTrue(wait >= 0 && wait < release, "publish.yml must wait for Central before"
                + " 'gh release create' (#966). Released first, a stalled Central publication"
                + " leaves a GitHub Release marked Latest whose coordinates 404, which is what"
                + " v1.13.0 shipped (#949)");
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
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
