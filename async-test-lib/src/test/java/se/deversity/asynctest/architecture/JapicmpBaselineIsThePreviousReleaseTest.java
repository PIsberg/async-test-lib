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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the japicmp baseline to the most recently released version.
 *
 * <p><strong>The failure this prevents, which has now happened six releases running.</strong>
 * {@code japicmp}'s {@code <oldVersion>} names the artifact the current build is compared
 * against, and it has to be the release users are upgrading off. When it lags, the gate compares
 * against an artifact predating everything the releases in between added — so it cannot fail on
 * breaking any of them. It sat at 1.6.0 while 1.7.0 through 1.9.1 shipped, and at 1.11.0 while
 * 1.11.2 was being cut.
 *
 * <p>What makes it worth a test rather than a checklist line is that <em>a stale baseline reports
 * nothing</em>. It does not warn, it does not fail; it just quietly stops protecting the API that
 * customers pin against, and the release goes green. The release skill and {@code docs/RELEASE.md}
 * both carry the instruction, and it was still missed every time, which is the signal that a
 * human step was the wrong mechanism.
 *
 * <p>Bumping it <em>forward</em> to the version being cut is the other half of the trap: the gate
 * then compares the release against itself, and the coordinate cannot resolve because it is not
 * published yet. So the correct value is the highest released version strictly below the version
 * in {@code pom.xml}, which is exactly what this asserts.
 *
 * <p>The released versions come from {@code docs/CHANGELOG.md}'s version headings rather than
 * from git tags: a test must not depend on the checkout having tags, and CI clones without them.
 */
class JapicmpBaselineIsThePreviousReleaseTest {

    private static final Pattern PROJECT_VERSION =
            Pattern.compile("^\\s*<version>([0-9][^<]*)</version>\\s*$");
    private static final Pattern CHANGELOG_RELEASE =
            Pattern.compile("^## \\[([0-9][0-9A-Za-z.\\-]*)\\]");
    private static final Pattern OLD_VERSION_BLOCK =
            Pattern.compile("<oldVersion>(.*?)</oldVersion>", Pattern.DOTALL);
    private static final Pattern VERSION_TAG =
            Pattern.compile("<version>([^<]+)</version>");

    @Test
    @DisplayName("japicmp compares against the previous release, not an older one and not itself")
    void baselineIsThePreviousRelease() {
        String projectVersion = projectVersion();
        String baseline = japicmpBaseline();
        List<String> released = releasedVersions();

        assertTrue(released.contains(baseline),
                "the japicmp baseline names " + baseline + ", which is not a released version in "
                        + "docs/CHANGELOG.md. It must name an artifact that exists on Central, or "
                        + "the gate cannot resolve it and stops comparing anything. Released: "
                        + released);

        String expected = released.stream()
                .filter(v -> compare(v, projectVersion) < 0)
                .max(JapicmpBaselineIsThePreviousReleaseTest::compare)
                .orElseThrow(() -> new AssertionError(
                        "no released version below " + projectVersion + " in docs/CHANGELOG.md"));

        assertEquals(expected, baseline,
                "japicmp's <oldVersion> in async-test-lib/pom.xml must be the release users are "
                        + "upgrading off, which for a build of " + projectVersion + " is "
                        + expected + ". A baseline older than that compares against an artifact "
                        + "predating what the releases in between added, so it cannot fail on "
                        + "breaking them - and it says nothing while doing it. A baseline equal "
                        + "to the version being cut cannot resolve at all, because that version "
                        + "is not published yet. Update it in the same change as the version "
                        + "bump; the release skill's step 3 says so too");
    }

    private static String projectVersion() {
        for (String line : read(repoRoot().resolve("pom.xml"))) {
            Matcher m = PROJECT_VERSION.matcher(line);
            if (m.matches()) {
                return m.group(1);
            }
        }
        throw new AssertionError("no <version> found in the reactor pom");
    }

    private static String japicmpBaseline() {
        String pom = String.join("\n", read(repoRoot().resolve("async-test-lib/pom.xml")));
        Matcher block = OLD_VERSION_BLOCK.matcher(pom);
        assertTrue(block.find(), "async-test-lib/pom.xml declares no japicmp <oldVersion>");
        Matcher version = VERSION_TAG.matcher(block.group(1));
        assertTrue(version.find(), "the japicmp <oldVersion> block declares no <version>");
        return version.group(1).trim();
    }

    /** Released versions, newest first, read from the changelog's version headings. */
    private static List<String> releasedVersions() {
        List<String> versions = new ArrayList<>();
        for (String line : read(repoRoot().resolve("docs/CHANGELOG.md"))) {
            Matcher m = CHANGELOG_RELEASE.matcher(line);
            if (m.find()) {
                versions.add(m.group(1));
            }
        }
        assertTrue(versions.size() > 1, "docs/CHANGELOG.md lists no released versions");
        return versions;
    }

    /** Compares two dotted versions numerically; a suffix like {@code -RC1} sorts below its base. */
    private static int compare(String a, String b) {
        List<Integer> left = key(a);
        List<Integer> right = key(b);
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            int l = i < left.size() ? left.get(i) : 0;
            int r = i < right.size() ? right.get(i) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static List<Integer> key(String version) {
        String base = version.contains("-") ? version.substring(0, version.indexOf('-')) : version;
        List<Integer> parts = new ArrayList<>();
        for (String piece : base.split("\\.")) {
            parts.add(Integer.parseInt(piece));
        }
        while (parts.size() < 3) {
            parts.add(0);
        }
        // A pre-release sorts below the same base version.
        parts.add(version.contains("-") ? 0 : 1);
        return parts;
    }

    private static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
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
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
