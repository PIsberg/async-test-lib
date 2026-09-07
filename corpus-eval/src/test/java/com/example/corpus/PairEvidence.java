package com.example.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.TrustTier;

/**
 * Which corpus pairs are strong enough to back {@code TrustTier.VERDICT}, decided from the rows.
 *
 * <p>The promotion channel has existed since 2026-08-29 and the bar is written down in
 * {@code docs/analysis/corpus-eval.md}: a pair backs VERDICT when it "varies the defect and
 * nothing else on the same class". Eleven detectors were promoted on that basis when the recording
 * lane held twelve pairs. The lane now holds 129 and nothing went back, because registering a pair
 * is a manual step and no gate notices a pair that never took it. That is how 116 detectors came to
 * sit at PROMPT holding evidence that already meets the stated bar - not measured and found
 * wanting, just never read.
 *
 * <p>This class derives the answer instead of listing it, so the backlog cannot go stale again.
 * Every pair lands in exactly one bucket:
 *
 * <ul>
 *   <li><b>Promoted</b> - named in {@code META-INF/async-test/verdict-evidence-corpus}, which both
 *       modules already check.</li>
 *   <li><b>Eligible</b> - both halves name the same class and reach the detector through the same
 *       set of {@code record*}/{@code register*} methods, so what separates them is the state the
 *       calls carry and nothing else. {@code EveryEligiblePairIsPromotedOrExplainedTest} fails
 *       until such a pair is promoted or explicitly reviewed and held.</li>
 *   <li><b>Held back</b> - with the reason derived, not written by hand.</li>
 * </ul>
 *
 * <p>Two rules hold a pair back, and both are conservative on purpose, because VERDICT is the one
 * tier safe to fail somebody's merge on.
 */
final class PairEvidence {

    private static final String RESOURCE = "/META-INF/async-test/verdict-evidence-corpus";

    /**
     * Pairs a reviewer accepted although the two halves call different detector methods.
     *
     * <p>The shape rule below is deliberately blunt and has a known false negative:
     * {@code RESOURCE_LEAKS} pairs an opened-and-never-closed row against opened-and-closed, so
     * the missing call <em>is</em> the defect and the pair is sound. Only a reader can tell that
     * from the {@code EXCHANGER} case, where the missing {@code recordExchangeStart} was incidental
     * and the pair claimed a defect the JDK permits (#521). So the escape hatch is a name and a
     * reason rather than a loosening of the rule.
     */
    private static final Map<DetectorType, String> REVIEWED_DESPITE_SHAPE =
            new EnumMap<>(DetectorType.class);

    static {
        // Empty on purpose. Adding an entry is a claim that someone read both bodies and found
        // the differing call to be the defect itself. An entry with no such reading is worth
        // less than the PROMPT tier it replaces.
    }

    private PairEvidence() {
    }

    /** Why a pair is not eligible, or {@code null} when it is. */
    enum HeldBack {

        /**
         * The two halves are different classes, so the class separates them as much as the defect
         * does. This is the rule that held three pairs back in the first promotion wave.
         */
        CROSS_CLASS("the two halves name different classes, so what separates fire from silence is "
                + "the class as much as the defect"),

        /**
         * The halves reach the detector through different methods. Sometimes the missing call is
         * the defect and the pair is sound; sometimes it is incidental and the pair proves less
         * than it looks. Only reading both bodies tells them apart.
         */
        CALL_SHAPE("the halves call different detector methods, so the pair may separate on which "
                + "call was made rather than on the state it carried - which is exactly the "
                + "mistake #521 found in the EXCHANGER pair"),

        /**
         * The detector's report carries per-finding grades, so its tier is the floor over every
         * grade it can emit and not a claim about any one of them.
         *
         * <p>{@code RecordMutableComponentLeakDetector} is the case that taught this. It reports
         * an observed mutation of a shared record's component at VERDICT grade and a shared record
         * that merely holds a mutable component at PROMPT, and is rated PROMPT overall because a
         * tier carries the weakest grade the detector can produce. Raising the detector would let
         * a {@code minTrust = VERDICT} gate admit the prompt-grade finding too, which is a claim
         * the library does not make. The corpus pair exercises one grade and cannot raise a floor
         * that exists because of the other.
         */
        GRADED("the detector's report implements GradedFindings, so its tier is the floor over "
                + "every grade it can emit; a pair exercises one grade and promoting the detector "
                + "would let a VERDICT-only gate admit the weaker ones");

        private final String reason;

        HeldBack(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    /** {@return the detectors already named in the cross-module evidence file} */
    static Set<DetectorType> promoted() {
        Set<DetectorType> promoted = EnumSet.noneOf(DetectorType.class);
        for (String raw : evidenceFile().split("\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            promoted.add(DetectorType.valueOf(line.substring(0, line.indexOf('=')).strip()));
        }
        return promoted;
    }

    /**
     * {@return the detectors whose pair meets the bar and is not promoted yet}
     *
     * <p>These are the ones the gate refuses to leave alone. Each has a same-class pair whose two
     * halves hand the detector the same kinds of evidence, which is the shape the eleven promoted
     * pairs have.
     */
    static Set<DetectorType> eligible() {
        Set<DetectorType> eligible = EnumSet.noneOf(DetectorType.class);
        Set<DetectorType> already = promoted();
        for (CorpusLane lane : List.of(CorpusLane.RECORDING, CorpusLane.AGENT_PAIRS)) {
            for (DetectorType detector : Corpus.pairedDetectors(lane)) {
                if (already.contains(detector)
                        || REVIEWED_DESPITE_SHAPE.containsKey(detector)
                        // Already gated, on evidence that lives inside the library. Asking for a
                        // corpus registration too would put one tier's justification in two
                        // places, and the second copy is the one that goes stale.
                        // Only PROMPT is a candidate. PROMPT is the tier that means "nobody
                        // measured the silent direction", which is exactly what a pair supplies.
                        // FACT and ADVISORY are statements about what kind of claim the finding
                        // is - FACT reports something observed and leaves the judgement to the
                        // reader - so a pair does not make either of them a verdict, and VERDICT
                        // is already gated.
                        || DetectorTrust.tierOf(detector) != TrustTier.PROMPT) {
                    continue;
                }
                // Only the lane that actually holds the pair can answer. Asking a lane where
                // the detector has no rows returns "nothing wrong here", and treating that as
                // eligibility made every recording-lane pair look eligible through the agent
                // lane - the whole roster, including pairs the shape rule exists to hold back.
                if (!hasPairIn(lane, detector)) {
                    continue;
                }
                if (heldBack(lane, detector) == null) {
                    eligible.add(detector);
                }
            }
        }
        eligible.removeAll(already);
        return eligible;
    }

    /**
     * {@return whether {@code detector}'s report grades its findings individually}
     *
     * <p>Resolved by reflection rather than from a list, because a list of seven class names is a
     * second copy of a fact the code already states, and the copy is the one that goes stale when
     * an eighth detector starts grading.
     *
     * @param detector the detector to inspect
     */
    static boolean carriesPerFindingGrades(DetectorType detector) {
        String name = "se.deversity.asynctest.diagnostics."
                + DetectorExposure.classOf(detector);
        Class<?> type;
        try {
            type = Class.forName(name);
        } catch (ClassNotFoundException e) {
            // A detector this module cannot load is one it cannot vouch for either. Holding it
            // back is the safe reading, and everyReportingDetectorWasExposed would have failed
            // first if the name were wrong in a way that mattered.
            return true;
        }
        if (GradedFindings.class.isAssignableFrom(type)) {
            return true;
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (GradedFindings.class.isAssignableFrom(nested)) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether {@code lane} holds both directions for {@code detector}} */
    private static boolean hasPairIn(CorpusLane lane, DetectorType detector) {
        Set<RecordingSubject.Expectation> directions =
                EnumSet.noneOf(RecordingSubject.Expectation.class);
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            if (subject.detector() == detector) {
                directions.add(subject.expectation());
            }
        }
        return directions.size() == 2;
    }

    /**
     * {@return why {@code detector}'s pair in {@code lane} is not eligible, or {@code null}}
     *
     * @param lane     the pair lane to read
     * @param detector the detector whose rows to weigh
     */
    static HeldBack heldBack(CorpusLane lane, DetectorType detector) {
        List<RecordingSubject> rows = Corpus.subjectsFor(lane).stream()
                .filter(subject -> subject.detector() == detector)
                .toList();
        Set<String> classes = new LinkedHashSet<>();
        Set<RecordingSubject.Expectation> directions = EnumSet.noneOf(
                RecordingSubject.Expectation.class);
        for (RecordingSubject row : rows) {
            classes.add(row.className());
            directions.add(row.expectation());
        }
        if (directions.size() < 2) {
            // Not a pair at all. everyPairIsAPair owns that, and reporting it here too would
            // give one defect two owners and two failure messages.
            return null;
        }
        if (carriesPerFindingGrades(detector)) {
            return HeldBack.GRADED;
        }
        if (classes.size() > 1) {
            return HeldBack.CROSS_CLASS;
        }
        if (lane == CorpusLane.AGENT_PAIRS) {
            // The shape rule cannot say anything here and must not pretend to. An agent-lane body
            // makes no record*/register* call at all - the woven call sites are the input - so
            // both halves measure as the empty set and any pair would compare equal. What holds
            // this lane to the same property is AgentRowPremise, which fails a silent row that
            // does not go through the same substituted call sites as its twin, and which runs as
            // a gate on every agent-lane run. That is the stronger form of what callShape
            // approximates, so a same-class agent pair is eligible on its strength.
            return null;
        }
        Set<String> fire = callShape(lane, rows, RecordingSubject.Expectation.MUST_FIRE);
        if (fire.isEmpty()) {
            // A firing row that reaches no detector method cannot be evidence of anything. Either
            // the row records nothing, or the source scan failed to find its body; both mean this
            // class cannot vouch for the pair, and neither is a promotion.
            return HeldBack.CALL_SHAPE;
        }
        return fire.equals(callShape(lane, rows, RecordingSubject.Expectation.MUST_STAY_SILENT))
                ? null
                : HeldBack.CALL_SHAPE;
    }

    /**
     * {@return the detector methods the rows of {@code direction} call, body and helpers}
     *
     * <p>Read from the lane's own source for the same reason {@link SilentRowPremise} does it: a
     * detector cannot be asked afterwards which of its methods a body used. The two classes look
     * at the same text for different properties - that one asks whether the detector was addressed
     * at all, this one asks whether both halves addressed it the same way.
     *
     * @param lane      the lane whose source to read
     * @param rows      that lane's rows for one detector
     * @param direction which half to measure
     */
    private static Set<String> callShape(CorpusLane lane,
                                         List<RecordingSubject> rows,
                                         RecordingSubject.Expectation direction) {
        String source = read(lane);
        Set<String> calls = new LinkedHashSet<>();
        for (RecordingSubject row : rows) {
            if (row.expectation() != direction) {
                continue;
            }
            Matcher found = Pattern.compile("\\.((?:record|register)[A-Z]\\w*)\\s*\\(")
                    .matcher(bodyWithHelpers(source, row.testMethod()));
            while (found.find()) {
                calls.add(found.group(1));
            }
        }
        return calls;
    }

    /** {@return {@code testMethod}'s body plus the bodies of the lane methods it calls} */
    private static String bodyWithHelpers(String source, String testMethod) {
        String body = bodyOf(source, testMethod);
        StringBuilder reachable = new StringBuilder(body);
        Matcher calls = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (calls.find()) {
            String name = calls.group(1);
            if (seen.add(name) && !name.equals(testMethod)) {
                reachable.append('\n').append(bodyOf(source, name));
            }
        }
        return reachable.toString();
    }

    /** {@return the source text of {@code methodName}'s body, or empty when there is none} */
    private static String bodyOf(String source, String methodName) {
        Matcher declaration = Pattern.compile(
                "(?m)^\\s*(?:@\\w+\\s+)*(?:private|public|protected|static|final|void|"
                        + "[A-Za-z<>\\[\\],.?\\s]+?)\\b"
                        + Pattern.quote(methodName)
                        + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[A-Za-z0-9_$.,\\s]+)?\\{")
                .matcher(source);
        if (!declaration.find()) {
            return "";
        }
        int open = source.indexOf('{', declaration.start());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return source.substring(open);
    }

    private static String read(CorpusLane lane) {
        Path source = Path.of("src", "test", "java", "com", "example", "corpus",
                lane == CorpusLane.AGENT_PAIRS
                        ? "CorpusAgentPairLaneTest.java"
                        : "CorpusRecordingLaneTest.java");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + source.toAbsolutePath()
                    + ". This reads the lane's own source, so it depends on the module directory "
                    + "being the working directory, which is how Surefire runs it", e);
        }
    }

    private static String evidenceFile() {
        try (InputStream in = PairEvidence.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath, so no pair "
                        + "can be read as promoted and this whole class would report the roster "
                        + "as an unpromoted backlog");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }
}
