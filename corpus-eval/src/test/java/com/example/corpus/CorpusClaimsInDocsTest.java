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
    private static final Path WORKFLOW = repoRoot().resolve(".github/workflows/corpus.yml");
    private static final Path MODULE_POM = repoRoot().resolve("corpus-eval/pom.xml");

    /**
     * The both-directions evidence that backs a VERDICT tier from this corpus.
     *
     * <p>Read from the working tree rather than from the library on the classpath, which this
     * module resolves from the local repository: a stale install would make this gate agree with
     * a jar instead of with the source the same pull request changes.
     */
    private static final Path VERDICT_EVIDENCE = repoRoot().resolve(
            "async-test-lib/src/main/resources/META-INF/async-test/verdict-evidence-corpus");

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
    @DisplayName("no source, workflow or pom here counts against a roster the library no longer ships")
    void theModulesOwnJavadocCountsAgainstTheCurrentRoster() throws IOException {
        int roster = DetectorType.values().length;
        Pattern denominator = Pattern.compile("of the (\\d+)\\b");

        List<String> stale = new ArrayList<>();
        for (Path source : claimBearingFiles()) {
            Matcher matcher = denominator.matcher(flattened(source));
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

    @Test
    @DisplayName("the workflow states the lane count and the VERDICT evidence this module produces")
    void theWorkflowHeaderAgreesWithTheModule() {
        int lanes = CorpusLane.values().length;
        long verdicts = corpusBackedVerdicts();

        List<String> stale = new ArrayList<>();
        checkCount(stale, WORKFLOW, flattened(WORKFLOW), lanes, "lanes per run",
                "CorpusLane declares " + lanes + " lanes and corpus-eval/pom.xml runs one Surefire "
                        + "execution for each of them");
        checkCount(stale, WORKFLOW, flattened(WORKFLOW), verdicts, "detectors carry VERDICT",
                "verdict-evidence-corpus names " + verdicts + " detectors whose VERDICT tier rests "
                        + "on a pair measured in this module");

        assertTrue(stale.isEmpty(),
                "the workflow header is what a reader who never opens this module reads about it, "
                        + "and it was also the only file describing the module that nothing here "
                        + "read: on 2026-09-17 it said three lanes where CorpusLane declares "
                        + lanes + ", and nine VERDICT detectors where the evidence file names "
                        + verdicts + ". Both had been true, two waves of corpus growth earlier: "
                        + stale);
    }

    /**
     * The floor above which "of the N" is read as a claim about the detector roster.
     *
     * <p>Without it this check would fail on "one of the 4 lanes" and every other small count that
     * happens to share the phrasing. A roster has been three digits since long before this module
     * existed, so the floor separates the two uses without a list of exceptions to maintain.
     */
    private static final int ROSTER_SIZED = 100;

    /**
     * {@return every file in or about this module whose prose states a count}
     *
     * <p>The Java sources, plus the two build files that describe the module to a reader who has
     * not opened it: the workflow that runs it, and the pom that declares its lanes. Both sat
     * outside every check in this class until 2026-09-17, and both had gone stale, one of them by
     * a whole lane count.
     */
    private static List<Path> claimBearingFiles() throws IOException {
        try (Stream<Path> tree = Files.walk(repoRoot().resolve("corpus-eval/src"))) {
            List<Path> files = new ArrayList<>(
                    tree.filter(path -> path.toString().endsWith(".java")).toList());
            files.add(WORKFLOW);
            files.add(MODULE_POM);
            return files;
        }
    }

    /**
     * {@return {@code file}'s text with comment markers and line breaks flattened away}
     *
     * <p>A claim in a build file wraps across lines that each carry the comment's indentation, and
     * in YAML a leading {@code #}. "five\n# lanes per run" is the same sentence as "five lanes per
     * run" and only the second is findable, so without this the checks here would report every
     * wrapped claim as missing and be deleted for crying wolf.
     */
    private static String flattened(Path file) {
        return read(file).replaceAll("(?m)^\\s*#\\s?", " ").replaceAll("\\s+", " ");
    }

    /**
     * {@return how many detectors carry a VERDICT tier on a pair measured in this module}
     *
     * <p>One line per detector in {@link #VERDICT_EVIDENCE}, comments and blanks aside. That file
     * is the thing {@code DetectorTrustCoverageTest} resolves a promotion against, so it is the
     * count the workflow header is claiming when it says how much rests on this eval.
     */
    private static long corpusBackedVerdicts() {
        return read(VERDICT_EVIDENCE).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("=", 2)[0].strip())
                .distinct()
                .count();
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
        checkCount(stale, document, read(document), count, noun, because);
    }

    /**
     * Records a stale claim unless {@code text} states {@code count} before {@code noun}.
     *
     * <p>The text is passed separately so a build file can be read through {@link
     * #flattened(Path)} while the failure still names the file a reader would open.
     */
    private static void checkCount(List<String> stale, Path document, String text, long count,
                                   String noun, String because) {
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
