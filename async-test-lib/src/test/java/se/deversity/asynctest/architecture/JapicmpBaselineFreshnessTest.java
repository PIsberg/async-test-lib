package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the japicmp baseline to the release the gate is supposed to be comparing against.
 *
 * <p><strong>Why this exists.</strong> japicmp is the only thing standing between a refactor and
 * a silently broken consumer, and it is disarmed by a single stale number. The baseline sat at
 * 1.6.0 while 1.7.0 through 1.9.1 shipped: for six releases the gate compared each build against
 * an artifact that predated every API those releases had added, so it could not have failed on
 * breaking any of them, and the 1.9.1 changelog's claim that japicmp was "green against 1.9.0"
 * described a comparison that never ran. Re-pinning is a manual step in
 * {@code docs/RELEASE.md}, it has been missed at four consecutive releases, and missing it
 * produces a green build rather than a warning. That is the shape of failure a test is for.
 *
 * <p><strong>What "fresh" means.</strong> The baseline must be the newest release below the
 * version being built - the one users are upgrading off. Two ways to get it wrong, and this test
 * refuses both:
 *
 * <ul>
 *   <li>Leaving it behind, which is what has happened every time so far: the gate compares
 *       against an artifact older than APIs the tree already ships.</li>
 *   <li>Bumping it forward to the version being cut, which {@code bump-version.sh} used to do.
 *       The gate then compares the release against itself, and the coordinate cannot even
 *       resolve, because it is not on Central yet.</li>
 * </ul>
 *
 * <p>The list of releases comes from {@code docs/CHANGELOG.md} rather than from git tags,
 * because CI clones are shallow and a tag-based check would quietly pass on an empty list.
 */
@DisplayName("The japicmp baseline is the previous release, not an older one")
class JapicmpBaselineFreshnessTest {

    /** The {@code <version>} of the reactor pom, which is the version being built. */
    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "<artifactId>async-test-parent</artifactId>\\s*<version>([^<]+)</version>");

    /** The {@code <version>} inside japicmp's {@code <oldVersion><dependency>} block. */
    private static final Pattern BASELINE_VERSION = Pattern.compile(
            "<oldVersion>.*?<version>([^<]+)</version>.*?</oldVersion>", Pattern.DOTALL);

    /** A released-version heading in the changelog: {@code ## [1.9.4] - 2026-08-16}. */
    private static final Pattern RELEASE_HEADING = Pattern.compile(
            "^## \\[(\\d+)\\.(\\d+)\\.(\\d+)\\]\\s+-\\s+\\d", Pattern.MULTILINE);

    @Test
    @DisplayName("japicmp compares against the newest release below the version being built")
    void baselineIsThePreviousRelease() {
        Path root = repoRoot();
        String modulePom = read(root.resolve("async-test-lib/pom.xml"));

        String projectVersion = firstGroup(PROJECT_VERSION, read(root.resolve("pom.xml")),
                "pom.xml has no <artifactId>async-test-parent</artifactId> followed by a "
                        + "<version>, so this test cannot tell which version is being built");
        String baseline = firstGroup(BASELINE_VERSION, modulePom,
                "async-test-lib/pom.xml has no japicmp <oldVersion> block. If the gate was "
                        + "removed on purpose, remove this test in the same change and say why "
                        + "in the changelog; a binary-compatibility gate should not disappear "
                        + "quietly");

        List<int[]> releases = releasedVersions(read(root.resolve("docs/CHANGELOG.md")));
        assertFalse(releases.isEmpty(),
                "docs/CHANGELOG.md lists no released versions, so this test has nothing to "
                        + "compare the baseline against and would pass without checking "
                        + "anything. Expected headings of the form '## [1.9.4] - 2026-08-16'");

        int[] building = parse(projectVersion);
        String expected = releases.stream()
                .filter(v -> compare(v, building) < 0)
                .max(Comparator.comparingInt((int[] v) -> v[0])
                        .thenComparingInt(v -> v[1])
                        .thenComparingInt(v -> v[2]))
                .map(JapicmpBaselineFreshnessTest::render)
                .orElseThrow(() -> new AssertionError(
                        "docs/CHANGELOG.md has no release older than the version being built ("
                                + projectVersion + "), so there is no baseline to pin. If this "
                                + "is the first release, pin the gate once that release is on "
                                + "Central."));

        assertNotEquals(projectVersion, baseline,
                "The japicmp baseline is pinned to " + baseline + ", which is the version being "
                        + "built. The gate would compare the release against itself, and the "
                        + "coordinate cannot resolve at all because it is not on Central yet. "
                        + "Pin it to the release you are upgrading users off, which is "
                        + expected + ". See docs/RELEASE.md, 'Where the version lives'.");

        assertEquals(expected, baseline,
                "The japicmp baseline in async-test-lib/pom.xml is " + baseline + ", but the "
                        + "newest release below the version being built (" + projectVersion
                        + ") is " + expected + ". A stale baseline does not report anything - it "
                        + "just stops protecting the API consumers pin against, which is how it "
                        + "sat at 1.6.0 through six releases. Re-pin <oldVersion> to " + expected
                        + ", or, if this release deliberately compares further back, say so in "
                        + "the changelog and update this test with the reason.");
    }

    // ---- helpers ----

    /** {@return every released version in the changelog, newest first in file order} */
    private static List<int[]> releasedVersions(String changelog) {
        List<int[]> versions = new ArrayList<>();
        Matcher m = RELEASE_HEADING.matcher(changelog);
        while (m.find()) {
            versions.add(new int[] {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))});
        }
        return versions;
    }

    private static int[] parse(String version) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        assertTrue(m.find(), "Version '" + version + "' is not MAJOR.MINOR.PATCH");
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))};
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < 3; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }

    private static String render(int[] version) {
        return version[0] + "." + version[1] + "." + version[2];
    }

    private static String firstGroup(Pattern pattern, String text, String whenMissing) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), whenMissing);
        return m.group(1).trim();
    }

    /**
     * Walks up from the working directory to the reactor root, the same way
     * {@code BuildMetadataSyncTest} does: surefire and Gradle both run one level down.
     */
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
