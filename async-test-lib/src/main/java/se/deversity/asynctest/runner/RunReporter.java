package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.diagnostics.DeadlockDetector;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.JfrPinningStream;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.report.Baseline;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a run says about itself: the detector reports and their trust banner, the {@code failOn}
 * gate on a passing run, and the report and error a failing or timed-out run ends with.
 *
 * <p>Split from {@link ConcurrencyRunner}, which decides how a run executes; this class decides
 * what it reports. It runs on the runner thread once the workers have finished or been quiesced,
 * so the detector state it reads is no longer being written.
 *
 * <p>It logs under the {@code ConcurrencyRunner} logger name: the {@code runner.*} events are a
 * pinned contract ({@code ConcurrencyRunnerLogContractTest}), and builds set log levels by that
 * name, so the split must not move a single event to a new logger.
 */
final class RunReporter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyRunner.class);

    /**
     * {@code -Dasync-test.report.full=true} prints PROMPT and ADVISORY reports in full on a
     * passing run; by default each is one line (see {@link #analyzeAndGate}).
     */
    static final String FULL_REPORT_PROPERTY = "async-test.report.full";

    private static final int FOLDED_HEADLINE_LIMIT = 160;

    /** At most this many {@code runner.detector.note} events per detector per run. */
    static final int NOTE_EVENTS_PER_DETECTOR = 3;

    private RunReporter() {
    }

    /**
     * Post-run detector analysis for tests whose body passed.
     *
     * <p>Before 1.7.0 detector findings were only reported when the test body had
     * already failed or timed out; a passing test never surfaced findings. This
     * method closes that gap: every enabled detector is analyzed, findings are
     * printed and fired to listeners, and findings at or above
     * {@link AsyncTestConfig#failOn} fail the test — unless suppressed by the
     * baseline file ({@code -Dasync-test.baseline=<path>}) or recorded to it in
     * update mode ({@code -Dasync-test.baseline.update=true}).
     *
     * <p>On the console, a block none of whose findings is FACT or VERDICT grade prints as one
     * line ({@link #foldedLine}) unless {@value #FULL_REPORT_PROPERTY} is {@code true} or the
     * block fails the run. A detectAll run enables every detector, and printed in full those
     * blocks buried the findings the library stands behind. Listeners get the full text always.
     *
     * <p>Only called on the success path (see {@link ConcurrencyRunner#execute}); failure/timeout paths
     * call {@link #printPhase2Reports} directly and never reach the failOn gate below.
     */
    static void analyzeAndGate(AsyncTestConfig config,
                                       Method testMethod,
                                       Phase2Analysis phase2Analysis) {
        Map<String, String> reports = new LinkedHashMap<>();
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            reports.putIfAbsent(finding.getKey(), "\n" + finding.getValue());
        }
        if (reports.isEmpty()) {
            return;
        }

        reportAndGate(config, testMethod, reports, phase2Analysis.grades(), phase2Analysis.severities(),
                phase2Analysis.messages(), true);
    }

    /**
     * {@link #analyzeAndGate}'s reporting half, for any set of reports: prints each, hands it to the
     * listeners, and, when {@code gate} is set, fails the test on the ones at or above failOn that
     * no baseline accepts. {@link ConcurrencyRunner#afterTeardown} reuses it for the verdicts held past
     * {@code @AfterEach}, ungated when the run had already failed.
     */
    static void reportAndGate(AsyncTestConfig config, Method testMethod, Map<String, String> reports,
                                      Map<String, List<GradedFindings.Grade>> graded,
                                      Map<String, IssueSeverity> structured,
                                      Map<String, List<String>> findingMessages, boolean gate) {
        String testId = testMethod.getDeclaringClass().getName() + "#" + ConcurrencyRunner.displayNameOf(testMethod);
        Baseline baseline = Baseline.fromSystemProperties();

        boolean fingerprinting = baseline.size() > 0 || Baseline.updateMode();
        boolean fullReports = Boolean.getBoolean(FULL_REPORT_PROPERTY);
        int folded = 0;
        int suppressed = 0;
        List<String> failing = new ArrayList<>();
        Map<String, List<String>> failingFingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : reports.entrySet()) {
            List<GradedFindings.Grade> grades = graded.getOrDefault(e.getKey(), List.of());
            List<String> fingerprints = fingerprinting
                    ? Baseline.fingerprints(e.getValue(), grades) : List.of();
            List<String> uncovered = new ArrayList<>();
            for (String fingerprint : fingerprints) {
                if (!baseline.covers(testId, e.getKey(), fingerprint)) {
                    uncovered.add(fingerprint);
                }
            }
            if (fingerprinting && uncovered.isEmpty()) {
                suppressed++;
                continue;
            }
            if (baseline.contains(testId, e.getKey())) {
                // An entry from before findings had fingerprints: it still accepts the whole
                // detector, but the user is told it is now hiding something it was not recorded for.
                suppressed++;
                log.info("baseline.detector-wide.suppressed test={} detector={} uncovered={} "
                        + "hint=\"a detector-wide baseline entry hides findings it was not recorded for; "
                        + "delete that line and rerun with -D{}=true to accept only the current ones\"",
                    testId, e.getKey(), uncovered.size(), Baseline.UPDATE_PROPERTY);
                continue;
            }
            // Gate only on what the baseline does not already accept: a covered verdict must not
            // fail the run because a new prompt beside it made the block print.
            List<GradedFindings.Grade> gated = grades.isEmpty() || !fingerprinting ? grades
                    : grades.stream().filter(g -> uncovered.contains(Baseline.fingerprint(g.summary()))).toList();
            IssueSeverity structuredSeverity = structured.get(e.getKey());
            boolean trips = gate && trips(config, e.getKey(), e.getValue(), structuredSeverity, gated);
            // A block that fails the run always prints in full: the assertion says "full reports above".
            if (!fullReports && !trips && foldsOnConsole(e.getKey(), grades)) {
                System.err.println(foldedLine(e.getKey(), e.getValue(), grades,
                        findingMessages.getOrDefault(e.getKey(), List.of())));
                folded++;
            } else {
                System.err.println(trustBanner(e.getKey(), grades));
                System.err.println(e.getValue());
            }
            AsyncTestListenerRegistry.fireDetectorReport(e.getKey(), e.getValue(), structuredSeverity, grades);
            if (trips) {
                failing.add(e.getKey());
                failingFingerprints.put(e.getKey(), fingerprints);
            }
        }
        if (folded > 0) {
            System.err.println("[AsyncTest] " + folded + " PROMPT/ADVISORY report(s) shown as one line each;"
                    + " rerun with -D" + FULL_REPORT_PROPERTY + "=true to print them in full");
        }
        if (suppressed > 0) {
            log.info("[AsyncTest] {} baselined finding(s) suppressed for {}", suppressed, testId);
        }
        if (failing.isEmpty()) {
            return;
        }

        if (Baseline.updateMode()) {
            int added = Baseline.record(testId, failingFingerprints);
            log.warn("[AsyncTest] Baseline update mode: recorded {} finding(s) for {} instead of failing",
                    added, testId);
            return;
        }

        AssertionError error = new AssertionError(
            "[AsyncTest] " + failing.size() + " detector finding(s) at or above failOn=" + config.failOn
            + " for " + testId + ": " + String.join(", ", failing)
            + ". Full reports above. To accept known findings, run with -D" + Baseline.PATH_PROPERTY
            + "=<file> (add -D" + Baseline.UPDATE_PROPERTY + "=true once to record them).");
        AsyncTestListenerRegistry.fireTestFailed(error);
        throw error;
    }

    /**
     * Reports a failed round that was not a timeout: the seed to replay it with, the
     * {@code onTestFailed} event, then the detector reports.
     *
     * <p>Report-only. The {@code failOn} gate ({@link #analyzeAndGate}) intentionally runs only
     * on the success path: a test that already failed does not need a second, synthetic
     * failure from detector findings on top of its own. The caller quiesces the workers first.
     */
    static void reportFailure(Throwable failure, long seed,
                                      ControlledSchedule.@Nullable Summary schedule,
                                      Phase2Analysis phase2Analysis) {
        // Surface the seed of the failing round, and say what pasting it into
        // @AsyncTest(replaySeed=N) replays: on its own only the seed's random choices, under a
        // controlled schedule the interleaving too, unless a worker escaped the schedule.
        System.err.println(replayHint(seed, schedule));
        AsyncTestListenerRegistry.fireTestFailed(failure);
        printPhase2Reports(phase2Analysis);
    }

    /**
     * The line a failing run prints about its seed. It used to say the seed reproduced the
     * failure, which only a controlled schedule with no escapes makes true.
     */
    static String replayHint(long seed, ControlledSchedule.@Nullable Summary schedule) {
        String head = "[AsyncTest] Failure with replaySeed=" + seed + "L — ";
        if (schedule == null) {
            return head + "paste into @AsyncTest(replaySeed=...) to replay the seed's random choices;"
                    + " add controlledSchedule = true to replay the interleaving too.";
        }
        if (schedule.escapes() == 0) {
            return head + "paste into @AsyncTest(replaySeed=..., controlledSchedule = true) to replay"
                    + " this interleaving.";
        }
        return head + "under controlledSchedule = true, but " + schedule.escapes() + " time(s) a worker"
                + " stopped without reaching a scheduling point (blocked, slept or waited) and left the"
                + " schedule, so a replay may interleave differently.";
    }

    /**
     * Reports a timeout exactly once — listeners notified, thread dump printed, detector
     * reports flushed — and returns the {@link ConcurrencyRunner.RoundTimeoutError} to throw.
     *
     * <p>Callers that throw the returned error from inside {@link ConcurrencyRunner#execute}'s try block must
     * set {@code timeoutAlreadyReported} first: the returned type satisfies
     * {@link ConcurrencyRunner#isTimeoutLike}, so the enclosing {@code catch (AssertionError)} would
     * otherwise route it through here a second time.
     *
     * <p>The message carries {@link #findingSummary}, which is the only place a timed-out run
     * says whether any detector had an answer: the {@code failOn} gate is success-path-only,
     * so the reports flushed above are all a reader gets, and in a parallel build they are
     * hundreds of interleaved lines from the failure. See {@link #findingSummary}.
     */
    static AssertionError timeoutError(long timeoutMs,
                                               @Nullable Throwable cause,
                                               Phase2Analysis phase2Analysis,
                                               boolean detectDeadlocks,
                                               ConcurrencyRunner.RunProgress progress) {
        AsyncTestListenerRegistry.fireTimeout(timeoutMs);
        if (detectDeadlocks) {
            DeadlockDetector.printThreadDump();
            DeadlockDetector.printLearningAndFix();
        }
        // Report-only: this is a failure/timeout path, so the failOn gate in
        // analyzeAndGate (success-path-only) is never reached for this run.
        printPhase2Reports(phase2Analysis);
        AssertionError error = new ConcurrencyRunner.RoundTimeoutError(progress.headline(timeoutMs) + findingSummary(phase2Analysis));
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    /**
     * One sentence naming the detectors that had something to say before the timeout.
     *
     * <p><strong>Why the failure needs this.</strong> {@link #analyzeAndGate} runs on the
     * success path only, so a run that timed out never reaches the {@code failOn} gate and
     * its failure is the timeout, not the finding. The findings are printed to stderr by the
     * caller, but a reader looking at a failing build sees the assertion message first and
     * often only that, and under {@code -T 1C} the reports belong to whichever module happened
     * to be writing at the time. Naming the detectors in the message is what makes "the round
     * timed out" and "here is what was detected" the same sentence.
     *
     * <p>The empty case is reported explicitly rather than omitted. "No enabled detector
     * produced a finding" is a different diagnosis from "a detector fired and you missed it",
     * and silence cannot distinguish them: it is exactly the ambiguity that let 21 example
     * demonstrations name a detector in their {@code @Disabled} reason while failing on a
     * timeout, with nothing to say which of them had a finding waiting (issue #363).
     *
     * <p>Names only, not reports. The reports are already on stderr in full, and an assertion
     * message that inlined them would push the timeout itself off the top of the console.
     */
    private static String findingSummary(Phase2Analysis phase2Analysis) {
        Set<String> named = phase2Analysis.get().keySet();
        if (named.isEmpty()) {
            return " No enabled detector produced a finding before the timeout.";
        }
        return " " + named.size() + " detector finding(s) recorded before the timeout: "
                + String.join(", ", named) + ". Full reports above.";
    }

    /**
     * The one line a reader sees before a finding's own report: which detector, and how far to
     * trust it.
     *
     * <p>A detectAll run enables every detector, and without this line a recorded deadlock and a
     * pattern the library cannot fully model print identically. A reader who cannot rank findings
     * treats the whole report as noise, so the rank goes first, above the detail.
     *
     * <p>Written to {@code System.err} beside the report rather than prepended to it: the report
     * string is what listeners receive and what tests assert on, and it stays untouched.
     */
    static String trustBanner(String detectorName, TrustTier tier) {
        return "[AsyncTest] " + detectorName + " trust=" + tier + " " + trustHint(tier);
    }

    private static String trustHint(TrustTier tier) {
        return switch (tier) {
            case VERDICT -> "(a finding means the code is wrong; decided from observed state or visible"
                    + " synchronization, and measured on the bug and on its correct twin)";
            case FACT -> "(the report states what was observed or recorded; whether it is a bug is your call)";
            case PROMPT -> "(a prompt to verify; synchronization the library cannot see may make this correct)";
            case ADVISORY -> "(a performance or hygiene note, not a correctness claim)";
        };
    }

    /**
     * Whether this detector's output should fail the run.
     *
     * <p>A detector that graded its findings is judged finding by finding: the run fails when
     * <em>any one</em> of them clears both thresholds. Judging the block as a whole is what made a
     * verdict-grade finding inherit the weakest tier its detector can produce, so a verdict-only
     * gate missed real bugs. Ungraded detectors keep the per-detector answer, which is still right
     * for a detector whose findings are all the same kind.
     */
    private static boolean trips(AsyncTestConfig config, String detectorName, String report,
                                 @Nullable IssueSeverity structuredSeverity,
                                 List<GradedFindings.Grade> grades) {
        if (grades.isEmpty()) {
            return config.failOn.triggeredBy(DetectorDefaultSeverity.of(detectorName, report, structuredSeverity))
                    && DetectorTrust.tierOfDetector(detectorName).atLeast(config.minTrust);
        }
        return grades.stream().anyMatch(grade ->
                config.failOn.triggeredBy(grade.severity()) && grade.tier().atLeast(config.minTrust));
    }

    /**
     * The banner for a detector whose report may grade its findings one by one.
     *
     * <p>The head line claims no more than the weakest finding under it supports: it names the
     * lowest tier, and the span when the grades differ ({@code trust=PROMPT..VERDICT}). It used to
     * name the best tier, so a block holding one observed mutation and one structural note was
     * headed "a finding means the code is wrong", and the note inherited that sentence. Each
     * graded finding then gets a line of its own with its tier and severity, so the reader can
     * tell which line is the verdict. The {@code failOn} gate is unaffected: {@link #trips} still
     * judges every finding on its own grade.
     *
     * <p>An ungraded detector gets the one-line banner of its detector-wide tier.
     */
    static String trustBanner(String detectorName, List<GradedFindings.Grade> grades) {
        if (grades.isEmpty()) {
            return trustBanner(detectorName, DetectorTrust.tierOfDetector(detectorName));
        }
        TrustTier lowest = grades.get(0).tier();
        TrustTier highest = lowest;
        for (GradedFindings.Grade grade : grades) {
            lowest = grade.tier().compareTo(lowest) < 0 ? grade.tier() : lowest;
            highest = grade.tier().compareTo(highest) > 0 ? grade.tier() : highest;
        }
        String span = lowest == highest ? lowest.toString() : lowest + ".." + highest;
        StringBuilder banner = new StringBuilder("[AsyncTest] ").append(detectorName)
                .append(" trust=").append(span).append(' ').append(trustHint(lowest));
        for (GradedFindings.Grade grade : grades) {
            banner.append(System.lineSeparator()).append("[AsyncTest]   finding trust=")
                    .append(grade.tier()).append(" severity=").append(grade.severity())
                    .append(": ").append(grade.summary());
        }
        return banner.toString();
    }

    /**
     * Whether a passing run prints this block as one line: when none of its findings is FACT or
     * VERDICT grade. A block holding a verdict among prompts prints in full, so folding never
     * hides a finding the library stands behind.
     */
    static boolean foldsOnConsole(String detectorName, List<GradedFindings.Grade> grades) {
        TrustTier best = grades.stream()
                .map(GradedFindings.Grade::tier)
                .max(java.util.Comparator.naturalOrder())
                .orElseGet(() -> DetectorTrust.tierOfDetector(detectorName));
        return !best.atLeast(TrustTier.FACT);
    }

    /**
     * One line for a folded block: detector, tier, finding count and the first finding's headline.
     *
     * <p>A graded report names its findings in its grades, and a report that keeps structured
     * findings names them in their messages. Only a report with neither, which since #801 is a
     * third-party detector's, is read by its text: one {@code "  - "} bullet per finding, or a
     * single finding headed by its first line. The bullets were the rule for every report until
     * #773, and they also list context and advice, so a folded line could count "Fix" text as
     * findings and lead with it.
     */
    static String foldedLine(String detectorName, String report, List<GradedFindings.Grade> grades,
                             List<String> findingMessages) {
        int count;
        String headline;
        TrustTier tier;
        if (grades.isEmpty() && !findingMessages.isEmpty()) {
            count = findingMessages.size();
            headline = findingMessages.get(0).strip();
            tier = DetectorTrust.tierOfDetector(detectorName);
        } else if (grades.isEmpty()) {
            List<String> bullets = report.lines().filter(l -> l.startsWith("  - ")).toList();
            count = Math.max(1, bullets.size());
            headline = bullets.isEmpty()
                    ? report.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("")
                    : bullets.get(0).substring(4).strip();
            tier = DetectorTrust.tierOfDetector(detectorName);
        } else {
            count = grades.size();
            headline = grades.get(0).summary();
            tier = grades.stream().map(GradedFindings.Grade::tier)
                    .min(java.util.Comparator.naturalOrder()).orElseThrow();
        }
        if (headline.length() > FOLDED_HEADLINE_LIMIT) {
            headline = headline.substring(0, FOLDED_HEADLINE_LIMIT) + "...";
        }
        return "[AsyncTest] " + detectorName + " trust=" + tier + " findings=" + count + ": " + headline;
    }

    /**
     * Prints every finding in full on the failure and timeout paths.
     *
     * <p>Deliberately not folded the way {@link #analyzeAndGate} folds PROMPT and ADVISORY blocks:
     * here the test has already failed on its own evidence, so a prompt-grade finding is no longer
     * noise but a candidate cause, and only failing tests reach this path, which bounds the output.
     */
    private static void printPhase2Reports(Phase2Analysis phase2Analysis) {
        Map<String, List<GradedFindings.Grade>> graded = phase2Analysis.grades();
        Map<String, IssueSeverity> structured = phase2Analysis.severities();
        for (Map.Entry<String, String> finding : phase2Analysis.get().entrySet()) {
            List<GradedFindings.Grade> grades = graded.getOrDefault(finding.getKey(), List.of());
            System.err.println("\n" + trustBanner(finding.getKey(), grades));
            System.err.println(finding.getValue());
            AsyncTestListenerRegistry.fireDetectorReport(finding.getKey(), finding.getValue(),
                    structured.get(finding.getKey()), grades);
        }
    }

    /**
     * Memoizes {@link AsyncTestContext#analyzeAll()} so it runs at most once per
     * {@link ConcurrencyRunner#execute}, no matter how many of the report/gate call sites
     * ({@link #printPhase2Reports}, {@link #timeoutError}, {@link #analyzeAndGate})
     * need the result.
     *
     * <p>{@code analyzeAll} is not guaranteed idempotent for stateful legacy detectors —
     * some accumulate state (deduplicators, violation lists) across calls rather than
     * computing purely from immutable snapshots — so calling it twice in the same run
     * risked double-counting or diverging findings between the two callers. Before this
     * cache existed, the pre-round deadline check in {@link ConcurrencyRunner#execute} could trigger
     * exactly that: it throws via {@link #timeoutError} (one {@code analyzeAll} call),
     * and since the resulting {@link AssertionError}'s message satisfies
     * {@link ConcurrencyRunner#isTimeoutLike}, the surrounding {@code catch (AssertionError e)} block
     * re-wrapped it via a second {@code timeoutError} call — running {@code analyzeAll}
     * a second time for the same test run.
     *
     * <p>Not synchronized: {@link ConcurrencyRunner#execute} only ever calls {@code get()} from the
     * single caller thread, after all worker threads for the current round have
     * finished (see the class Javadoc on {@link AsyncTestContext#analyzeAll()}).
     */
    static final class Phase2Analysis {
        private final AsyncTestContext ctx;
        private final String testName;
        private @Nullable Map<String, String> reports;
        private @Nullable JfrPinningStream pinningStream;

        Phase2Analysis(AsyncTestContext ctx, String testName) {
            this.ctx = ctx;
            this.testName = testName;
        }

        /** Sets the stream {@link #get()} stops before analyzing; {@code null} when there is none. */
        void drainBeforeAnalysis(@Nullable JfrPinningStream stream) {
            this.pinningStream = stream;
        }

        /**
         * {@return the per-finding grades of this run, keyed by detector}
         *
         * <p>Runs {@link #get()} first: the grades are a by-product of the analysis pass, so
         * reading them before it has run would report the previous pass's, or nothing at all.
         */
        Map<String, List<GradedFindings.Grade>> grades() {
            get();
            return ctx.findingGrades();
        }

        /** {@return the structured severities of this run, keyed by detector; runs {@link #get()} first} */
        Map<String, IssueSeverity> severities() {
            get();
            return ctx.findingSeverities();
        }

        /** {@return the structured finding messages of this run, keyed by detector; runs {@link #get()} first} */
        Map<String, List<String>> messages() {
            get();
            return ctx.findingMessages();
        }

        /** {@return the findings of this run, keyed by the detector that produced each} */
        Map<String, String> get() {
            Map<String, String> memo = reports;
            if (memo == null) {
                // Last chance for agent-captured accesses still in the ring buffer to reach
                // the detectors. The periodic drain runs every millisecond, so without this the
                // final round could contribute events or not depending on timing. No-op when
                // the registry is not running, i.e. whenever the agent is not attached.
                TelemetryRegistry.flush();
                // JFR delivers pins in batches and had delivered none of them before stop() in
                // the 2026-10-10 probe, so the analysis sees them only after this. Every report
                // path comes through here, the failure and timeout reports included.
                JfrPinningStream pins = pinningStream;
                if (pins != null) {
                    pins.close();
                }
                memo = ctx.analyzeAllNamed();
                reports = memo;
                logNotes(testName, ctx.detectorNotes());
            }
            return memo;
        }
    }

    /**
     * Logs the notes of detectors whose report had no finding, one INFO event per distinct note.
     *
     * <p>Such a report is never printed, so a note in it (a hint that asks the caller to change a
     * recording, such as an undecided {@code SynchronizedNonFinalDetector} slot) reached nobody
     * (#816). A note is not a finding, so it goes to the diagnostic channel at INFO rather than
     * into the report or at WARN. Here, in the memoized analysis, it is said once per run on
     * every path, and at most {@value #NOTE_EVENTS_PER_DETECTOR} times per detector; the
     * {@code notes=} field keeps the total so a reader knows when lines were left out.
     */
    private static void logNotes(String testName, Map<String, List<String>> notes) {
        for (Map.Entry<String, List<String>> e : notes.entrySet()) {
            List<String> lines = e.getValue();
            for (int i = 0; i < Math.min(lines.size(), NOTE_EVENTS_PER_DETECTOR); i++) {
                log.info("runner.detector.note test={} detector={} notes={} note=\"{}\"",
                        testName, e.getKey(), lines.size(), lines.get(i).replace("\"", "\\\""));
            }
        }
    }
}
