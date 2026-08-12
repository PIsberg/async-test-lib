package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.DetectorType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the published detector count honest in the documentation.
 *
 * <p><strong>Why this exists.</strong> {@link DetectorType} is the only real count, and
 * {@code BuildMetadataSyncTest} already pins the pom and Gradle descriptions against it. The prose
 * had no such anchor, so it drifted: on 2026-08-12 the docs simultaneously claimed 111, 127, 128
 * and 135 detectors across six files, and {@code DETECTOR_CATALOG.md} numbered only 128 of its 135
 * entries — six lettered A-F and one with no marker at all — leaving a gap at 121-127 that read as
 * seven missing detectors and cost real time to disprove.
 *
 * <p>Numbers in prose are a claim a reader can check in a minute, and a wrong one is expensive out
 * of proportion to its size: it invites the question of what else was never measured. These two
 * tests make the claim CI-enforced rather than a thing someone remembers to update.
 */
class DetectorCatalogCoverageTest {

    /**
     * Documents whose numbers are historical rather than current claims.
     *
     * <p>The changelog records what each release said at the time. {@code docs/analysis/} holds
     * investigation write-ups that quote the numbers they found — including wrong ones, which is
     * the point of the record. Rewriting either would destroy evidence to satisfy a gate.
     */
    private static final List<String> HISTORICAL = List.of("CHANGELOG.md");

    /** Path fragment for the analysis write-ups, exempt for the reason above. */
    private static final String ANALYSIS_DIR = "analysis";

    /**
     * Counts that are about somebody else's detectors.
     *
     * <p>Currently one: find-sec-bugs ships 121 detectors of its own, counted from the plugin
     * jar's {@code findbugs.xml}. That number has nothing to do with {@link DetectorType} and must
     * not be dragged to 135 by a gate that cannot read context. Listed explicitly, with the
     * owning file, so a second unrelated count cannot hide behind a blanket exemption.
     */
    private static final Map<String, Integer> THIRD_PARTY_COUNTS =
            Map.of("QUALITY_GATES.md", 121);

    /** A detector entry heading: {@code ### 42. Some Detector}. */
    private static final Pattern ENTRY = Pattern.compile("^### (\\d+)\\. ", Pattern.MULTILINE);

    /**
     * A prose claim about how many detectors this library has.
     *
     * <p>The lookbehinds exclude phrases where the number names something else and merely sits
     * next to the word: "Phase 10 detectors" is a phase label, and "JDK 25/26 detectors" is a
     * platform version. Both were flagged by a first version of this pattern, which is a good
     * reminder that a gate reporting things that are not defects gets switched off.
     */
    private static final Pattern CLAIM =
            Pattern.compile("(?<!Phase )(?<!JDK )(?<![0-9]/)\\b(\\d{2,4}) detector");

    @Test
    @DisplayName("the catalog documents every detector, numbered contiguously")
    void catalogCoversEveryDetectorType() {
        String catalog = read(repoRoot().resolve("docs/DETECTOR_CATALOG.md"));

        SortedSet<Integer> numbers = new TreeSet<>();
        List<Integer> all = new ArrayList<>();
        Matcher m = ENTRY.matcher(catalog);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            numbers.add(n);
            all.add(n);
        }

        int expected = DetectorType.values().length;

        assertEquals(expected, all.size(),
                "DETECTOR_CATALOG.md has " + all.size() + " numbered entries but DetectorType "
                        + "declares " + expected + ". Every detector needs an entry, and an entry "
                        + "that carries a letter or no marker at all does not count as one — that "
                        + "is exactly how this drifted into looking like seven detectors were "
                        + "undocumented when they were merely unnumbered.");

        assertEquals(all.size(), numbers.size(),
                "DETECTOR_CATALOG.md reuses an entry number. Duplicates make the catalog "
                        + "un-navigable and hide a missing entry behind a matching total.");

        assertEquals(1, numbers.first(), "Catalog numbering must start at 1.");
        assertEquals(expected, numbers.last(),
                "Catalog numbering must run to " + expected + " with no gaps. Highest number seen: "
                        + numbers.last() + ".");
    }

    @Test
    @DisplayName("no document states a detector count other than the real one")
    void proseDetectorCountsMatchDetectorType() {
        int expected = DetectorType.values().length;
        Path docs = repoRoot().resolve("docs");

        Map<String, List<String>> wrong = new LinkedHashMap<>();
        for (Path file : markdownFiles(docs)) {
            String name = file.getFileName().toString();
            if (HISTORICAL.contains(name) || file.getParent().endsWith(ANALYSIS_DIR)) {
                continue;
            }
            collectWrongClaims(read(file), expected, name,
                    repoRoot().relativize(file).toString(), wrong);
        }
        Path readme = repoRoot().resolve("README.md");
        if (Files.isRegularFile(readme)) {
            collectWrongClaims(read(readme), expected, "README.md", "README.md", wrong);
        }

        assertTrue(wrong.isEmpty(),
                "These documents state a detector count that is not " + expected + ", which is "
                        + "what DetectorType actually declares:\n"
                        + render(wrong)
                        + "\nA reader can check this in under a minute, and a number that fails "
                        + "that check costs more trust than its size deserves. Update the prose, "
                        + "or if the count genuinely changed, let this test tell you every place "
                        + "that needs to follow. Historical statements belong in "
                        + HISTORICAL + ", which is exempt.");
    }

    private static void collectWrongClaims(String body, int expected, String fileName,
                                           String label, Map<String, List<String>> into) {
        Integer thirdParty = THIRD_PARTY_COUNTS.get(fileName);
        Matcher m = CLAIM.matcher(body);
        while (m.find()) {
            int claimed = Integer.parseInt(m.group(1));
            if (claimed == expected || (thirdParty != null && claimed == thirdParty)) {
                continue;
            }
            into.computeIfAbsent(label, k -> new ArrayList<>())
                    .add(m.group(0).trim() + " (should be " + expected + ")");
        }
    }

    private static String render(Map<String, List<String>> wrong) {
        StringBuilder sb = new StringBuilder();
        wrong.forEach((file, claims) ->
                sb.append("  ").append(file).append(": ").append(claims).append('\n'));
        return sb.toString();
    }

    private static List<Path> markdownFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding pom.xml and docs/) above "
                        + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
