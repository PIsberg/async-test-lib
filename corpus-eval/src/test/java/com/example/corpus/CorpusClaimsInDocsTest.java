package com.example.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.Set;
import java.util.TreeSet;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the corpus numbers printed in prose to the corpus that produced them.
 *
 * <p>Every count in {@code README.md} and {@code docs/analysis/corpus-eval.md} is a claim about
 * this module, and until now nothing checked one. The claims went stale the same afternoon the
 * corpus grew: the README said 42 subjects, 20 of 20 and 0 of 22 while the module measured 82, 22
 * of 22 and 0 of 60, and its own agent bullet said 21 in the same breath as the evidence section
 * said 5. A reader evaluating the tool reads those two numbers before running anything.
 *
 * <p>This checks the denominators rather than the results, because they are what the module can
 * answer without a run: how many subjects the corpus holds, and how they split by contract. The
 * per-run outcomes stay in the generated reports under {@code target/corpus-eval/}, which the
 * documents already name as the authority when the two disagree.
 *
 * <p>The check is deliberately for the number in its sentence, not a regex over every integer in
 * the file. A gate that fails whenever any digit moves gets weakened until it passes; this one
 * fails only when a stated denominator stops being true.
 */
class CorpusClaimsInDocsTest {

    private static final Path README = repoRoot().resolve("README.md");
    private static final Path EVAL = repoRoot().resolve("docs/analysis/corpus-eval.md");
    private static final Path MODULE_README = repoRoot().resolve("corpus-eval/README.md");

    @Test
    @DisplayName("the subject counts in README and the corpus eval match the corpus")
    void proseAgreesWithTheCorpus() {
        long safe = Corpus.count(Contract.THREAD_SAFE);
        long unsafe = Corpus.count(Contract.NOT_THREAD_SAFE);
        long total = Corpus.subjects().size();

        List<String> stale = new ArrayList<>();
        check(stale, README, total + " subjects",
                "the corpus holds " + total + " subjects");
        check(stale, README, unsafe + " of " + unsafe + " detected",
                "the documented-unsafe group is " + unsafe + " subjects");
        check(stale, README, "0 of " + safe,
                "the documented-safe group is " + safe + " subjects");
        check(stale, EVAL, total + " subjects",
                "the corpus holds " + total + " subjects");
        check(stale, EVAL, safe + " documented-safe",
                "the documented-safe group is " + safe + " subjects");

        // The pairing roster and the library reach, both derived rather than counted. The README
        // said "twelve more detectors" and "Nine of those pairs" while the lanes grew to pair 131,
        // because nothing read that sentence either.
        int roster = DetectorType.values().length;
        long paired = DetectorCoverage.paired().size();
        long refused = DetectorCoverage.refused().size();
        long reached = LibraryReach.reached().size();
        long agentFed = LibraryReach.agentFed().size();
        check(stale, README, paired + " of the " + roster + " detectors are paired",
                "DetectorCoverage pairs " + paired + " detectors");
        check(stale, README, "the other " + refused + " carry a written reason",
                "DetectorCoverage refuses " + refused + " detectors");
        for (Path document : List.of(README, EVAL)) {
            check(stale, document, reached + " of the " + agentFed + " agent-fed detectors",
                    "LibraryReach measures " + reached + " of " + agentFed
                            + " agent-fed detectors on a call site inside a library");
        }

        assertTrue(stale.isEmpty(),
                "these documents state corpus numbers the corpus no longer produces. The generated "
                        + "reports under target/corpus-eval/ are the authority and the prose is a "
                        + "copy of one run, so the copy is what has to move: " + stale);
    }

    @Test
    @DisplayName("the module's own README states the corpus it ships with, and its lanes")
    void theModuleReadmeAgreesWithTheModule() {
        long total = Corpus.subjects().size();
        long classes = Corpus.subjects().stream().map(Subject::className).distinct().count();
        long libraries = corpusLibraries().size();
        long agentFed = java.util.Arrays.stream(DetectorType.values())
                .filter(type -> DetectorFeeds.feedOf(type) == DetectorFeed.AGENT)
                .count();
        int lanes = CorpusLane.values().length;

        List<String> stale = new ArrayList<>();
        check(stale, MODULE_README, total + " subjects drawn from " + classes,
                "the corpus holds " + total + " subjects over " + classes + " distinct classes");
        checkCount(stale, MODULE_README, lanes, "lanes and writes one report per lane",
                "CorpusLane declares " + lanes + " lanes");
        checkCount(stale, MODULE_README, libraries, "corpus libraries",
                "the corpus draws subjects from " + libraries + " third-party libraries besides "
                        + "the JDK, each a dependency this module puts on a classpath");
        checkCount(stale, MODULE_README, agentFed, "agent-fed detectors",
                "DetectorFeeds classifies " + agentFed + " detectors as AGENT-fed, which is what "
                        + "the agent-off lane is the control for");
        for (CorpusLane lane : CorpusLane.values()) {
            check(stale, MODULE_README, "`" + lane.propertyValue() + "`",
                    "CorpusLane declares the " + lane.propertyValue() + " lane, and the lane "
                            + "table is what a reader uses to find its report");
        }

        assertTrue(stale.isEmpty(),
                "corpus-eval/README.md is the first thing a reader of this module opens, and it "
                        + "states numbers the module no longer produces. This is the drift that "
                        + "went unnoticed until 2026-09-07 precisely because nothing read it: "
                        + stale);
    }

    @Test
    @DisplayName("no source in this module counts against a roster the library no longer ships")
    void theModulesOwnJavadocCountsAgainstTheCurrentRoster() throws IOException {
        int roster = DetectorType.values().length;
        Pattern denominator = Pattern.compile("of the (\\d+)\\b");

        List<String> stale = new ArrayList<>();
        for (Path source : sources()) {
            Matcher matcher = denominator.matcher(read(source));
            while (matcher.find()) {
                int stated = Integer.parseInt(matcher.group(1));
                if (stated >= ROSTER_SIZED && stated != roster) {
                    stale.add(repoRoot().relativize(source) + " says \"of the " + stated
                            + "\", and the library ships " + roster + " detectors");
                }
            }
        }

        assertTrue(stale.isEmpty(),
                "these sentences divide by a detector roster that no longer exists. Four of them "
                        + "named a roster of 142 for long enough that it grew twice underneath "
                        + "them, which is what a count nobody reads does. Re-read the sentence "
                        + "rather than only the number: the numerator usually moved too. Note "
                        + "that this check reads source text, so an example written into a "
                        + "comment counts as a claim: phrase one so it does not: " + stale);
    }

    /**
     * The floor above which "of the N" is read as a claim about the detector roster.
     *
     * <p>Without it this check would fail on "one of the 4 lanes" and every other small count that
     * happens to share the phrasing. A roster has been three digits since long before this module
     * existed, so the floor separates the two uses without a list of exceptions to maintain.
     */
    private static final int ROSTER_SIZED = 100;

    /** {@return every Java source in this module} */
    private static List<Path> sources() throws IOException {
        try (Stream<Path> tree = Files.walk(repoRoot().resolve("corpus-eval/src"))) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /**
     * {@return every third-party library the corpus draws a subject from, JDK classes aside}
     *
     * <p>Across every lane, not only {@link Corpus#subjects()}. The sentence this backs is about
     * what the module puts on a classpath, and HikariCP earns its dependency by appearing in
     * recording rows alone - counting eval subjects would say seven and quietly mean something
     * else. That is the same mistake in miniature as the one this test exists to catch.
     */
    private static Set<String> corpusLibraries() {
        List<String> all = new ArrayList<>();
        Corpus.subjects().forEach(subject -> all.add(subject.library()));
        for (CorpusLane lane : CorpusLane.values()) {
            Corpus.subjectsFor(lane).forEach(subject -> all.add(subject.library()));
        }
        Set<String> libraries = new TreeSet<>();
        for (String library : all) {
            if (!library.startsWith("jdk:")) {
                libraries.add(library.substring(0, library.lastIndexOf(':')));
            }
        }
        return libraries;
    }

    /**
     * Records a stale claim unless {@code document} states {@code count} before {@code noun}.
     *
     * <p>Accepts the digit or the English word, because the documents write small counts as words
     * and a gate that forced "4 lanes" into that prose would be paid for in readability by every
     * future sentence. This is still the number in its sentence rather than a regex over every
     * integer in the file: the noun has to follow it.
     *
     * @param stale    where a failure is collected
     * @param document the file whose prose is the claim
     * @param count    what the module actually produces
     * @param noun     the words the count must precede
     * @param because  what the module says instead, for the failure message
     */
    private static void checkCount(List<String> stale, Path document, long count, String noun,
                                   String because) {
        String text = read(document);
        if (!text.contains(count + " " + noun) && !text.contains(word(count) + " " + noun)) {
            stale.add(repoRoot().relativize(document) + " no longer says \"" + count + " " + noun
                    + "\" (or \"" + word(count) + " " + noun + "\"), and " + because);
        }
    }

    /** {@return the English word for {@code count}, or its digits past the ones prose spells out} */
    private static String word(long count) {
        List<String> words = List.of("zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve");
        return count >= 0 && count < words.size() ? words.get((int) count) : String.valueOf(count);
    }

    private static void check(List<String> stale, Path document, String claim, String because) {
        if (!read(document).contains(claim)) {
            stale.add(repoRoot().relativize(document) + " no longer says \"" + claim
                    + "\", and " + because);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /** {@return the reactor root, found by walking up from this module's directory} */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("README.md"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }
}
