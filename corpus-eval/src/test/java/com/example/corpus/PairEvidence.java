package com.example.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     *
     * <p>An entry lifts the shape rule and nothing else: the pair becomes eligible, and
     * {@code EveryEligiblePairIsPromotedOrExplainedTest} then requires it to be promoted. Before
     * that was so, an entry here skipped eligibility outright, which would have let a reviewed
     * pair sit unpromoted with nothing noticing - the same gap the promotion gate exists to close.
     */
    private static final Map<DetectorType, String> REVIEWED_DESPITE_SHAPE =
            new EnumMap<>(DetectorType.class);

    /**
     * Pairs read and held back on the detector's model, not on the pair.
     *
     * <p>VERDICT means a finding says the code is wrong. A pair can vary exactly the defect and
     * still not earn that, when correct code reached another way draws the same finding, or the
     * finding is decided by a number the body supplied. {@code verdict-evidence-corpus} records
     * three such detectors in prose; these are the ones found by reading pairs the shape rule
     * held back, recorded as data so that the backlog of pairs nobody has read is a number
     * {@link #unreviewed()} derives rather than one a document states.
     *
     * <p>An entry is a claim about the detector as it is today. Each reason names what would have
     * to change for the question to be worth asking again, and {@link #staleReviews()} fails an
     * entry once the detector is no longer a PROMPT candidate with a pair.
     */
    private static final Map<DetectorType, String> HELD_ON_MODEL = new EnumMap<>(DetectorType.class);

    static {
        // REVIEWED_DESPITE_SHAPE is empty on purpose. Adding an entry is a claim that someone read
        // both bodies and found the differing call to be the defect itself. An entry with no such
        // reading is worth less than the PROMPT tier it replaces.

        // Read 2026-09-14: each pair's two bodies and its detector's source, by a reviewer asked
        // to argue against promotion. The reason is the model property that decided it.
        HELD_ON_MODEL.put(DetectorType.STREAM_CLOSING, "reports any stream still open when "
                + "analysis runs and any close from a thread other than the opener, so a stream "
                + "scoped to the class and closed in teardown, or handed to a consumer thread that "
                + "closes it, draws the leak finding; needs a declared lifetime and no "
                + "cross-thread rule on the reporting path");
        HELD_ON_MODEL.put(DetectorType.EXECUTOR_SHUTDOWN, "a finding means the ownership "
                + "declaration disagrees with the executor's state at analysis, not that a pool "
                + "leaked: a pool the body declared but a class-scoped @AfterAll or a shutdown "
                + "hook closes reads as never shut down, because analysis runs first; needs a "
                + "declared lifetime (#568 made the executor, not the records, decide timed-out "
                + "awaits and try-with-resources)");
        HELD_ON_MODEL.put(DetectorType.FUTURE_IGNORED, "the body declares both the submit and "
                + "the inspection and the detector only compares the two, so fire-and-forget by "
                + "design and a whenComplete handler (a different Future identity) fire, while "
                + "isDone() counts as handling the exception; needs to observe get/join itself");
        HELD_ON_MODEL.put(DetectorType.THREAD_LOCAL_LEAKS, "a finding means a value was left "
                + "set at the end of a round, which a withInitial per-thread cache meant to stay "
                + "on a pooled thread does by design, and which leaks nothing on the default "
                + "virtual threads; needs a declaration of which values are request-scoped "
                + "(#565 made cleanup per thread and per round and stopped counting removed "
                + "values as retained)");
        HELD_ON_MODEL.put(DetectorType.SCHEDULED_EXECUTOR, "the separator is durationMs > 1000 on "
                + "a duration the body passes in; pool size and queued work are never consulted, "
                + "so a dedicated scheduler running one slow nightly job fires and a real overrun "
                + "under a second stays silent; needs a queue-behind model");
        HELD_ON_MODEL.put(DetectorType.TIMER, "the long-running trigger compares a task's "
                + "run-to-complete time against 100 ms, so a GC pause or a loaded runner makes a "
                + "correct task fire, and an exception recorded off the timer thread is still "
                + "taken at its word; needs the duration off the reporting path (#567 made thread "
                + "death observed on the timer thread and the clock monotonic)");
        // LOCK_UPGRADE_DEADLOCK was held here on reading only the body's records; #566 made it
        // ask the lock, and it is promoted in verdict-evidence-corpus.
        HELD_ON_MODEL.put(DetectorType.RACE_CONDITIONS, "has no happens-before edge but the round "
                + "epoch and cannot see an undeclared lock, so a field guarded by one (pinned in "
                + "DetectorAccuracyEvalTest), a volatile read of a field written under a lock, and "
                + "a confined hand-off all fire; needs ordering the recording API does not carry "
                + "(#570 replaced fingerprint equality with lockset intersection)");
        // READ_WRITE_LOCK_FAIRNESS was held here as a liveness observation; #569 moved it to
        // ADVISORY, which is not a promotion candidate, so the hold no longer asks anything.

        // Second reading, 2026-09-14 (#571). In all eight the body declares what the finding says
        // and the detector never asks the object it names.
        HELD_ON_MODEL.put(DetectorType.CONDITION_VARIABLES, "pairs each recorded await with the "
                + "signals recorded while it waited (#583), but still never asks the Condition: "
                + "the MUST_FIRE row's finding is the body's own declaration that an await woke "
                + "with no signal; needs the lock's hasWaiters at analysis, or a real parked "
                + "waiter in the unsafe body, to decide on what the object did");
        HELD_ON_MODEL.put(DetectorType.CYCLIC_BARRIER, "recordTimeout is still the finding and is "
                + "never checked against the barrier, so a timed await that handles its "
                + "TimeoutException fires the same as one that strands its parties; needs the "
                + "timeout observed, then a re-read (#584 decided reuse with isBroken() at the "
                + "await and dropped the bare left-broken finding)");
        HELD_ON_MODEL.put(DetectorType.EXCHANGER, "#585 made orphaning the finding, decided from "
                + "recorded starts against completions, timeouts and interrupts, so a handled timeout "
                + "is silent; still held until re-read, because the counts are the body's own "
                + "declaration and an Exchanger exposes no waiter count to check a start against");
        HELD_ON_MODEL.put(DetectorType.MISSED_SIGNAL, "counts a notify with no recorded waiter as "
                + "lost, which is harmless whenever the waiter checks a state predicate, and the "
                + "class javadoc's own example is that correct pattern; needs a wait observed not "
                + "to re-check its predicate, on the real monitor");
        HELD_ON_MODEL.put(DetectorType.PHASER, "recordTermination is the finding, and termination "
                + "is how a phaser normally ends, so arriveAndDeregister to zero draws CRITICAL; "
                + "needs an arrive or register observed returning a negative phase after it");
        HELD_ON_MODEL.put(DetectorType.STAMPED_LOCK, "a leak is reported only when the body calls "
                + "recordStampNotReleased and an unmatched acquisition is never inferred, so the "
                + "finding is the caller's assertion; needs stamp matching, or the real lock's "
                + "isWriteLocked and getReadLockCount at analysis");
        HELD_ON_MODEL.put(DetectorType.REENTRANT_LOCK, "recordLockTimeout is the finding, so a "
                + "tryLock timeout the caller handles is reported the same as one it discards, and "
                + "whether the false return was discarded is invisible here; TRY_LOCK_MISUSE, which "
                + "observes the caller's use of the result, is the detector that can say it");
        HELD_ON_MODEL.put(DetectorType.WAKEUP_ISSUES, "the wasNotified flag the body passes is the "
                + "spurious-wakeup finding, a wakeup is a defect only if the waiter skips "
                + "re-checking its condition, which is never seen, and a notify with no waiter, "
                + "the correct flag-then-notifyAll handshake, counts as lost");
        // Read before this map existed; the full argument is in verdict-evidence-corpus.
        HELD_ON_MODEL.put(DetectorType.FILE_CHANNEL_POSITION_RACE, "reports whenever more than "
                + "one thread accessed the channel and carries no representation of a lock, so a "
                + "caller that wraps position(n) and read(buffer) in synchronized(channel) draws "
                + "the identical finding; needs the lockset the Shared* family already has");
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
                + "would let a VERDICT-only gate admit the weaker ones"),

        /**
         * A reader went through the pair and the detector and found that a finding would not mean
         * the code is wrong, whatever the pair shows. The reason is per detector, in
         * {@link #HELD_ON_MODEL}.
         */
        MODEL("read and held back on the detector's model: correct code reached another way draws "
                + "the same finding, or the outcome is a number the body supplied");

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
        HeldBack byRule = heldBackByRule(lane, detector);
        if (HELD_ON_MODEL.containsKey(detector) && hasPairIn(lane, detector)) {
            return HeldBack.MODEL;
        }
        if (byRule == HeldBack.CALL_SHAPE && REVIEWED_DESPITE_SHAPE.containsKey(detector)) {
            return null;
        }
        return byRule;
    }

    /**
     * {@return the PROMPT detectors whose pair the shape rule holds back and nobody has read}
     *
     * <p>This is the backlog item 2b of {@code corpus-eval-future-improvements.md} describes, derived
     * so that the document can be held to it. A pair leaves it by being read: promoted after an
     * entry in {@link #REVIEWED_DESPITE_SHAPE}, or recorded in {@link #HELD_ON_MODEL}.
     */
    static Set<DetectorType> unreviewed() {
        Set<DetectorType> unreviewed = EnumSet.noneOf(DetectorType.class);
        Set<DetectorType> already = promoted();
        for (DetectorType detector : Corpus.pairedDetectors(CorpusLane.RECORDING)) {
            if (!already.contains(detector)
                    && DetectorTrust.tierOf(detector) == TrustTier.PROMPT
                    && hasPairIn(CorpusLane.RECORDING, detector)
                    && heldBack(CorpusLane.RECORDING, detector) == HeldBack.CALL_SHAPE) {
                unreviewed.add(detector);
            }
        }
        return unreviewed;
    }

    /**
     * {@return a line per review entry that no longer reads a live question}
     *
     * <p>An entry outlives its question when the detector left PROMPT, was promoted, lost its
     * pair, or - for {@link #REVIEWED_DESPITE_SHAPE} - stopped being held back by the shape rule,
     * so that the entry now vouches for nothing. Left in place it reads as a decision somebody
     * made about the detector as it is, when it was made about the detector as it was.
     */
    static List<String> staleReviews() {
        List<String> stale = new ArrayList<>();
        Set<DetectorType> already = promoted();
        for (DetectorType detector : HELD_ON_MODEL.keySet()) {
            String why = staleness(detector, already);
            if (why != null) {
                stale.add("HELD_ON_MODEL names " + detector + ", which " + why);
            }
        }
        for (DetectorType detector : REVIEWED_DESPITE_SHAPE.keySet()) {
            // Promotion is what an accepted reading is for, so unlike a hold this entry stays
            // live after it: it is the only record of why a pair of differing calls backs VERDICT.
            if (heldBackByRule(CorpusLane.RECORDING, detector) != HeldBack.CALL_SHAPE) {
                stale.add("REVIEWED_DESPITE_SHAPE names " + detector + ", which the shape rule no "
                        + "longer holds back, so the entry lifts nothing");
            }
        }
        return stale;
    }

    private static String staleness(DetectorType detector, Set<DetectorType> already) {
        if (already.contains(detector)) {
            return "is promoted";
        }
        if (DetectorTrust.tierOf(detector) != TrustTier.PROMPT) {
            return "is " + DetectorTrust.tierOf(detector) + ", not a PROMPT candidate";
        }
        if (!hasPairIn(CorpusLane.RECORDING, detector) && !hasPairIn(CorpusLane.AGENT_PAIRS, detector)) {
            return "has no pair in either lane";
        }
        return null;
    }

    /** {@return why the derived rules hold {@code detector}'s pair back, ignoring every review} */
    private static HeldBack heldBackByRule(CorpusLane lane, DetectorType detector) {
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
            calls.addAll(detectorCallsIn(LaneSource.bodyWithHelpers(source, row.testMethod())));
        }
        return calls;
    }

    /**
     * {@return the detector methods {@code body} calls}
     *
     * <p>Two receivers are not detectors although their methods share the prefix.
     * {@code CorpusRecorder.recordCrash} is the harness keeping a thrown exception, and
     * {@code AsyncTestContext.record...Detector()} is an accessor returning the detector. Counting
     * either made a pair differ on a call the detector never received: that is what held the
     * {@code NOTIFY_WITHOUT_MONITOR} pair back, whose firing half hands the JVM's
     * {@code IllegalMonitorStateException} to the recorder and is otherwise the silent half.
     *
     * @param body source text of a test body and its helpers
     */
    static Set<String> detectorCallsIn(String body) {
        Set<String> calls = new LinkedHashSet<>();
        Matcher found = Pattern.compile("(\\w+)?\\s*\\.\\s*((?:record|register)[A-Z]\\w*)\\s*\\(")
                .matcher(body);
        while (found.find()) {
            // Set.of refuses a null probe, and a chained call has no receiver name.
            if (found.group(1) == null || !NOT_DETECTORS.contains(found.group(1))) {
                calls.add(found.group(2));
            }
        }
        return calls;
    }

    private static final Set<String> NOT_DETECTORS = Set.of("CorpusRecorder", "AsyncTestContext");

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
