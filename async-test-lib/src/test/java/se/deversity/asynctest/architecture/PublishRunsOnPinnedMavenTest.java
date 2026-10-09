package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish Release deploys with a pinned Maven below 3.10, not the runner image's (#949).
 *
 * <p>The ubuntu-24.04 image moved from Maven 3.9.16 to 3.10.0 in build 20261004.327. Under 3.10.0,
 * central-publishing-maven-plugin 0.11.0 (the newest) stages into a directory Maven treats as a
 * local repository, so the bundle also carries {@code .locks/}, {@code _remote.repositories} and a
 * {@code maven-metadata-local.xml} beside each module's version directory. The portal rejected
 * v1.13.0's bundle for exactly those four directories ("Bundle has content that does NOT have a
 * .pom file") and published nothing. Reproduced locally on one commit: 79 bundle entries under
 * 3.10.0, 42 under 3.9.16. When a plugin release bundles correctly under 3.10, raise the pin and
 * this bound together.
 */
class PublishRunsOnPinnedMavenTest {

    private static final Pattern PINNED = Pattern.compile("MAVEN_VERSION: (\\d+)\\.(\\d+)\\.(\\d+)");

    @Test
    @DisplayName("publish.yml installs a checksum-verified Maven below 3.10 before the deploy")
    void publishDeploysOnPinnedMaven() throws IOException {
        String publish = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"),
                StandardCharsets.UTF_8);
        int deploy = publish.indexOf("clean deploy");
        assertTrue(deploy >= 0, "publish.yml no longer deploys; this test reads the wrong file");

        Matcher pinned = PINNED.matcher(publish);
        assertTrue(pinned.find() && pinned.start() < deploy, "publish.yml must pin MAVEN_VERSION in a"
                + " step before the deploy; the runner image's Maven 3.10.0 builds a bundle Central"
                + " rejects (#949)");
        int major = Integer.parseInt(pinned.group(1));
        int minor = Integer.parseInt(pinned.group(2));
        assertTrue(major == 3 && minor < 10, "pinned Maven " + major + "." + minor + " is one"
                + " central-publishing-maven-plugin 0.11.0 bundles wrongly under (#949)");

        String install = publish.substring(publish.lastIndexOf("- name:", pinned.start()), deploy);
        assertTrue(install.contains("sha512sum --check"), "the Maven download must be checked"
                + " against a pinned sha512 before it runs the release build");
        assertTrue(install.contains("GITHUB_PATH"), "the pinned Maven must go on GITHUB_PATH, or the"
                + " deploy step still runs the image's mvn");
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
