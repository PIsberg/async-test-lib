package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish Release builds its Central bundle with a Maven and a central-publishing-maven-plugin that
 * bundle correctly together (#949, #974).
 *
 * <p>The ubuntu-24.04 image moved from Maven 3.9.16 to 3.10.0 in build 20261004.327. Under 3.10.0,
 * central-publishing-maven-plugin 0.11.0 staged into a directory Maven treats as a local repository,
 * so the bundle also carried {@code .locks/}, {@code _remote.repositories} and a
 * {@code maven-metadata-local.xml} beside each module's version directory; the portal rejected
 * v1.13.0's bundle for those and published nothing. publish.yml then installed Maven 3.9.16 for the
 * release. With plugin 0.12.0 the bundle is the same under both: on 2026-10-20 the release profile's
 * bundle, built with a dead upload URL, held the same 105 files under 3.10.0 and 3.9.16 and none of
 * those entries, so the pin came off (#974).
 *
 * <p>The rule kept is the combination: the release may run on the image's Maven only with plugin
 * 0.12.0 or newer, and a pinned Maven must stay below 3.10, so going back to 0.11.0 without a pin
 * fails here rather than at the portal.
 */
class PublishBundlesOnASafeMavenTest {

    private static final Pattern PINNED = Pattern.compile("MAVEN_VERSION: (\\d+)\\.(\\d+)\\.(\\d+)");

    private static final Pattern PLUGIN = Pattern.compile(
            "<central-publishing-maven-plugin\\.version>(\\d+)\\.(\\d+)\\.(\\d+)</central-publishing-maven-plugin\\.version>");

    /** The first plugin release whose bundle under Maven 3.10.0 holds no local-repository files. */
    private static final int[] FIRST_SAFE_PLUGIN = {0, 12, 0};

    /**
     * {@return whether a release on this Maven, with this plugin, bundles what Central accepts}
     *
     * @param pinnedMaven the Maven publish.yml installs, or empty for the runner image's
     * @param plugin      the central-publishing-maven-plugin version
     */
    static boolean bundlesSafely(Optional<int[]> pinnedMaven, int[] plugin) {
        if (pinnedMaven.isPresent()) {
            int[] maven = pinnedMaven.get();
            return maven[0] == 3 && maven[1] < 10;
        }
        return compare(plugin, FIRST_SAFE_PLUGIN) >= 0;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    @Test
    @DisplayName("the rule refuses the combination that published nothing, and only that")
    void theRuleRefusesTheV1130Combination() {
        int[] old = {0, 11, 0};
        int[] fixed = {0, 12, 0};
        assertFalse(bundlesSafely(Optional.empty(), old), "the image's Maven 3.10 with plugin 0.11.0 is v1.13.0");
        assertTrue(bundlesSafely(Optional.empty(), fixed), "plugin 0.12.0 bundles correctly under 3.10.0");
        assertTrue(bundlesSafely(Optional.of(new int[] {3, 9, 16}), old), "the 3.9.16 pin made 0.11.0 safe");
        assertFalse(bundlesSafely(Optional.of(new int[] {3, 10, 0}), old), "a pin at 3.10 is no pin");
    }

    @Test
    @DisplayName("publish.yml and the root pom name a Maven and a plugin that bundle correctly together")
    void publishBundlesOnASafeMaven() throws IOException {
        String publish = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"),
                StandardCharsets.UTF_8);
        int deploy = publish.indexOf("clean deploy");
        assertTrue(deploy >= 0, "publish.yml no longer deploys; this test reads the wrong file");
        Matcher pinned = PINNED.matcher(publish);
        Optional<int[]> maven = pinned.find() && pinned.start() < deploy
                ? Optional.of(new int[] {Integer.parseInt(pinned.group(1)),
                        Integer.parseInt(pinned.group(2)), Integer.parseInt(pinned.group(3))})
                : Optional.empty();
        if (maven.isPresent()) {
            String install = publish.substring(publish.lastIndexOf("- name:", pinned.start()), deploy);
            assertTrue(install.contains("sha512sum --check") && install.contains("GITHUB_PATH"),
                    "a pinned Maven must be checked against a pinned sha512 and put on GITHUB_PATH");
        }

        String pom = Files.readString(repoRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher plugin = PLUGIN.matcher(pom);
        assertTrue(plugin.find(), "the root pom no longer declares central-publishing-maven-plugin.version");
        int[] version = {Integer.parseInt(plugin.group(1)), Integer.parseInt(plugin.group(2)),
                Integer.parseInt(plugin.group(3))};
        assertTrue(bundlesSafely(maven, version), "publish.yml runs on "
                + maven.map(m -> "a pinned Maven " + m[0] + "." + m[1] + "." + m[2]).orElse("the runner image's Maven")
                + " with central-publishing-maven-plugin " + version[0] + "." + version[1] + "." + version[2]
                + ", a combination whose bundle Central rejects (#949, #974): pin Maven below 3.10 or use"
                + " the plugin 0.12.0 or newer");
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
