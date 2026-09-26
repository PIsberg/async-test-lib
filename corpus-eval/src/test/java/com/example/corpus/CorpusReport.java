package com.example.corpus;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders the measured run as markdown, next to the surefire reports.
 *
 * <p>The numbers in {@code docs/analysis/corpus-eval.md} are copied from this output. Nothing here
 * decides anything: the gates live in {@link CorpusGates}.
 *
 * <p>Every rate is printed over the denominator it was measured against. A finding count on its
 * own cannot distinguish a detector that looked and saw nothing from one that was never fed, and
 * in this corpus 125 of the 146 are never fed at all, so the exposure section is not decoration.
 */
final class CorpusReport {

    private CorpusReport() {
    }

    /** A finding is counted as a false positive when the library's own strongest claim is wrong. */
    static boolean isFalsePositive(CorpusRecorder.Finding finding, Subject subject) {
        return subject.contract() == Contract.THREAD_SAFE
                && finding.tier() == TrustTier.VERDICT
                && (finding.severity() == IssueSeverity.HIGH || finding.severity() == IssueSeverity.CRITICAL);
    }

    static Path write(List<CorpusRecorder.Finding> findings,
                      List<CorpusRecorder.Crash> crashes,
                      int threads,
                      int invocations,
                      CorpusLane lane) {
        StringBuilder out = new StringBuilder();
        out.append("# Corpus eval run\n\n")
                .append("- Lane: ").append(lane.propertyValue()).append('\n')
                .append("- JVM: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vm.name")).append(")\n")
                .append("- OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n")
                .append("- Configuration: threads=").append(threads)
                .append(", invocations=").append(invocations)
                .append(", detectAll=true, agent=")
                .append(System.getProperty("asynctest.agent", "(not attached)")).append('\n')
                .append("- Body executions: ").append(CorpusRecorder.bodyExecutions()).append('\n')
                .append(LibraryBuild.describe()).append('\n')
                // An access the buffer threw away is an access no detector saw. A run that lost
                // events has weaker evidence than its finding list looks like, in both directions,
                // so the number belongs next to the numbers it qualifies rather than in a log.
                .append("- Telemetry events published: ")
                .append(TelemetryRegistry.publishedEvents())
                .append(", dropped: ").append(TelemetryRegistry.droppedEvents())
                .append("\n\n");

        out.append("## Per subject\n\n")
                .append("| Subject | Library | Contract | Events | Findings | Detectors (tier/severity) | Crashes |\n")
                .append("|---|---|---|---:|---:|---|---:|\n");

        for (Subject subject : Corpus.subjects()) {
            List<CorpusRecorder.Finding> mine = findings.stream()
                    .filter(finding -> finding.subject().equals(subject.testMethod()))
                    .toList();
            long crashCount = crashes.stream()
                    .filter(crash -> crash.subject().equals(subject.testMethod()))
                    .count();
            Set<String> detectors = new LinkedHashSet<>();
            for (CorpusRecorder.Finding finding : mine) {
                detectors.add(finding.detector() + " (" + finding.tier() + "/" + finding.severity() + ")");
            }
            out.append("| `").append(subject.testMethod()).append("` | ")
                    .append(subject.library()).append(" | ")
                    .append(subject.contract()).append(" | ")
                    .append(CorpusRecorder.eventsFor(subject.testMethod())).append(" | ")
                    .append(mine.size()).append(" | ")
                    .append(detectors.isEmpty() ? "-" : String.join(", ", detectors)).append(" | ")
                    .append(crashCount).append(" |\n");
        }

        out.append('\n').append(exposure(findings, lane));
        out.append('\n').append(summary(findings, crashes, lane));

        Path target = Path.of("target", "corpus-eval", lane.reportFile());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the corpus report", e);
        }
        return target;
    }

    /**
     * Renders the recording lane, whose columns answer a different question.
     *
     * <p>The other lanes ask what unmodified code draws. This one asks whether a detector's model
     * separates correct usage from incorrect usage when both are handed the same shape of
     * evidence, so the table is organised as pairs and prints the expectation next to the result.
     * Keeping it in its own file is deliberate: merging these numbers into the unmodified ones
     * would let a rate measured over cooperating bodies be read as a rate over untouched code.
     *
     * @param findings    what the detectors reported
     * @param threads     threads per subject
     * @param invocations rounds per subject
     * @param lane        the lane that ran
     * @return the file written
     */
    static Path writeRecording(List<CorpusRecorder.Finding> findings,
                               int threads,
                               int invocations,
                               CorpusLane lane) {
        StringBuilder out = new StringBuilder();
        out.append("# Corpus eval run - ").append(lane.propertyValue()).append(" lane\n\n")
                .append("The subjects are the same unmodified third-party classes the other lanes ")
                .append(lane == CorpusLane.AGENT_PAIRS || lane == CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED
                        ? "use, except that these subjects are JDK types. What differs is the "
                        + "test body, which records nothing and gets its entire input from the "
                        + "call sites the agent substitutes. These numbers are a "
                        : "use. What differs is the test body, which calls the recording API the "
                        + "way a user following `AsyncTestContext` would. These numbers are a ")
                .append("different measurement over a different denominator and must not be ")
                .append("merged into the unmodified lanes'.\n\n")
                .append("- Lane: ").append(lane.propertyValue()).append('\n')
                .append("- JVM: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vm.name")).append(")\n")
                .append("- OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n")
                .append("- Configuration: threads=").append(threads)
                .append(", invocations=").append(invocations)
                .append(", detectAll=true, agent=")
                .append(lane == CorpusLane.AGENT_PAIRS
                        ? "fields=true,collections=true (the only feed in this lane)\n"
                        : lane == CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED
                        ? "fields=true,collections=true with the library packages excluded, so every "
                        + "library row's firing half must be silent\n"
                        : "(not attached, on purpose)\n")
                .append("- Body executions: ").append(CorpusRecorder.bodyExecutions()).append('\n')
                .append(LibraryBuild.describe()).append("\n\n");

        out.append("## Per subject\n\n")
                .append("| Subject | Library | Detector | Class contract | Expected | Observed | Result |\n")
                .append("|---|---|---|---|---|---|---|\n");

        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            String detectorClass = DetectorExposure.classOf(subject.detector());
            List<CorpusRecorder.Finding> mine = findings.stream()
                    .filter(finding -> finding.subject().equals(subject.testMethod()))
                    .filter(finding -> finding.detector().equals(detectorClass))
                    .toList();
            boolean fired = !mine.isEmpty();
            boolean shouldFire = subject.expectation() == RecordingSubject.Expectation.MUST_FIRE;
            out.append("| `").append(subject.testMethod()).append("` | ")
                    .append(subject.library()).append(" | `")
                    .append(detectorClass).append("` | ")
                    .append(subject.contract()).append(" | ")
                    .append(subject.expectation()).append(" | ")
                    .append(fired ? "fired (" + mine.size() + ", " + severitiesOf(mine) + ")" : "silent")
                    .append(" | ")
                    .append(fired == shouldFire ? "as stated" : "**MISMATCH**").append(" |\n");
        }

        out.append("\nWhy each row must come out as it does:\n\n");
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            out.append("- `").append(subject.testMethod()).append("` - ")
                    .append(subject.rationale()).append(".\n");
        }

        out.append(collateral(findings, lane));
        out.append('\n').append(recordingExposure(findings, lane));
        out.append('\n').append(recordingSummary(findings, lane));

        Path target = Path.of("target", "corpus-eval", lane.reportFile());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the recording-lane report", e);
        }
        return target;
    }

    /**
     * Renders the idiom lane, where every finding on a correct row is printed, at every tier.
     *
     * <p>The pair lanes print each row filtered to the detector it names, because that is the
     * row's whole claim. A correct idiom claims more: that nothing worth failing on is said about
     * it by anything. So the table prints every detector that spoke, and the tier each spoke at,
     * which is also the only place the sub-{@code FACT} questions the gate lets through are
     * written down.
     *
     * @param findings    what the detectors reported
     * @param threads     threads per subject
     * @param invocations rounds per subject
     * @param lane        the lane that ran
     * @return the file written
     */
    static Path writeIdioms(List<CorpusRecorder.Finding> findings,
                            int threads,
                            int invocations,
                            CorpusLane lane) {
        StringBuilder out = new StringBuilder();
        out.append("# Corpus eval run - ").append(lane.propertyValue()).append(" lane\n\n")
                .append("Correct user-code concurrency idioms, each followed by its broken twin, ")
                .append("with every detector on and the agent attached. A correct row must draw ")
                .append("nothing at FACT tier or above from any detector and nothing at any tier ")
                .append("from the detector it names; a twin must wake its named detector.\n\n")
                .append("- Lane: ").append(lane.propertyValue()).append('\n')
                .append("- JVM: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vm.name")).append(")\n")
                .append("- OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n")
                .append("- Configuration: threads=").append(threads)
                .append(", invocations=").append(invocations)
                .append(", detectAll=true, agent=fields=true,collections=true\n")
                .append("- Body executions: ").append(CorpusRecorder.bodyExecutions()).append('\n')
                .append(LibraryBuild.describe()).append("\n\n");

        out.append("## Per row\n\n")
                .append("| Row | Expected | Named detector | Named detector said | FACT or above, any detector | Below FACT, other detectors | Events |\n")
                .append("|---|---|---|---|---|---|---:|\n");
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            String own = DetectorExposure.classOf(subject.detector());
            List<CorpusRecorder.Finding> mine = findings.stream()
                    .filter(finding -> finding.subject().equals(subject.testMethod()))
                    .toList();
            List<CorpusRecorder.Finding> fromOwn = mine.stream()
                    .filter(finding -> finding.detector().equals(own)).toList();
            List<CorpusRecorder.Finding> strong = mine.stream()
                    .filter(finding -> CorpusGates.claimedTier(finding).atLeast(TrustTier.FACT))
                    .toList();
            List<CorpusRecorder.Finding> weakOthers = mine.stream()
                    .filter(finding -> !CorpusGates.claimedTier(finding).atLeast(TrustTier.FACT))
                    .filter(finding -> !finding.detector().equals(own)).toList();
            String expected = subject.expectation() == RecordingSubject.Expectation.MUST_FIRE
                    ? "twin: fires"
                    : Corpus.idiomKnownGaps().containsKey(subject.testMethod())
                            ? "correct: known gap"
                            : subject.expectedSeverity() != null
                                    ? "correct: " + subject.expectedSeverity() + " note"
                                    : "correct: silent";
            out.append("| `").append(subject.testMethod()).append("` | ")
                    .append(expected).append(" | `").append(own).append("` | ")
                    .append(fromOwn.isEmpty() ? "silent" : tiersOf(fromOwn)).append(" | ")
                    .append(strong.isEmpty() ? "-" : detectorsOf(strong)).append(" | ")
                    .append(weakOthers.isEmpty() ? "-" : detectorsOf(weakOthers)).append(" | ")
                    .append(CorpusRecorder.eventsFor(subject.testMethod())).append(" |\n");
        }

        out.append("\nWhy each row must come out as it does:\n\n");
        for (RecordingSubject subject : Corpus.subjectsFor(lane)) {
            out.append("- `").append(subject.testMethod()).append("` - ")
                    .append(subject.rationale()).append(".\n");
        }
        if (!Corpus.idiomKnownGaps().isEmpty()) {
            out.append("\n## Known gaps\n\n");
            Corpus.idiomKnownGaps().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(gap -> out.append("- `").append(gap.getKey()).append("` - ")
                            .append(gap.getValue()).append(".\n"));
        }
        if (!Corpus.idiomManualApiRows().isEmpty()) {
            out.append("\n## Rows that call the manual API\n\n");
            Corpus.idiomManualApiRows().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(row -> out.append("- `").append(row.getKey()).append("` - ")
                            .append(row.getValue()).append(".\n"));
        }
        out.append('\n').append(recordingExposure(findings, lane));
        out.append('\n').append(recordingSummary(findings, lane));

        Path target = Path.of("target", "corpus-eval", lane.reportFile());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the idiom-lane report", e);
        }
        return target;
    }

    /** {@return each finding as tier/severity, for a table cell} */
    private static String tiersOf(List<CorpusRecorder.Finding> findings) {
        return findings.stream()
                .map(finding -> finding.tier() + "/" + finding.severity())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** {@return each finding as detector tier/severity, for a table cell} */
    private static String detectorsOf(List<CorpusRecorder.Finding> findings) {
        return findings.stream()
                .map(finding -> "`" + finding.detector() + "` " + finding.tier() + "/"
                        + finding.severity())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * The recording lane's exposure, which cannot borrow the other lanes' framing.
     *
     * <p>There the denominator is the subject's documented contract, because the body does
     * nothing but share the instance. Here the body cooperates and the contract is beside the
     * point: {@code recorded_concurrentReferenceHashMap_checkThenAct} is a thread-safe class
     * used wrongly, and counting it as a "safe subject with a finding" would read as a false
     * positive when it is the opposite. So the per-detector table is organised by what each row
     * claims must happen.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that ran
     * @return the exposure section
     */
    static String recordingExposure(List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        long subjects = Corpus.subjectsFor(lane).size();

        StringBuilder out = new StringBuilder("## Detector exposure\n\n")
                .append("Exposure here is not the whole RECORDING feed. A detector is exposed ")
                .append("only if a subject actually records to it; claiming all 141 because the ")
                .append("lane records to some of them would trade one unreadable denominator ")
                .append("for another.\n\n")
                .append("| Feed | Detectors | Exposed in this lane | Subject-detector pairs exposed | Pairs with a finding |\n")
                .append("|---|---:|---:|---:|---:|\n");

        long exposedTotal = 0;
        long pairsTotal = 0;
        long firedPairsTotal = 0;
        for (DetectorFeed feed : DetectorFeed.values()) {
            Set<DetectorType> ofFeed = DetectorExposure.fedBy(feed);
            long exposed = ofFeed.stream().filter(type -> DetectorExposure.isExposed(type, lane)).count();
            long pairs = exposed * subjects;
            long fired = firedPairs(findings, feed);
            exposedTotal += exposed;
            pairsTotal += pairs;
            firedPairsTotal += fired;
            out.append("| ").append(feed).append(" | ").append(ofFeed.size()).append(" | ")
                    .append(exposed).append(" | ").append(pairs).append(" | ").append(fired).append(" |\n");
        }
        out.append("| **Total** | **").append(DetectorTrust.DETECTOR_COUNT).append("** | **")
                .append(exposedTotal).append("** | **").append(pairsTotal).append("** | **")
                .append(firedPairsTotal).append("** |\n\n");

        out.append("Per detector this lane records to, counted by what each subject claims:\n\n")
                .append("| Detector | Subjects | Must fire | ...that did | Must stay silent | ...that did |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");

        for (DetectorType type : Corpus.pairedDetectors(lane)) {
            List<RecordingSubject> mine = Corpus.subjectsFor(lane).stream()
                    .filter(subject -> subject.detector() == type)
                    .toList();
            long mustFire = mine.stream()
                    .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE).count();
            long didFire = mine.stream()
                    .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                    .filter(s -> firedFor(findings, s)).count();
            long mustBeSilent = mine.size() - mustFire;
            long wasSilent = mine.stream()
                    .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_STAY_SILENT)
                    .filter(s -> !firedFor(findings, s)).count();
            out.append("| `").append(DetectorExposure.classOf(type)).append("` | ")
                    .append(mine.size()).append(" | ")
                    .append(mustFire).append(" | ").append(didFire).append(" | ")
                    .append(mustBeSilent).append(" | ").append(wasSilent).append(" |\n");
        }
        return out.toString();
    }

    /**
     * {@return the recording lane's summary}
     *
     * <p>Both directions are counted separately, because a single "subjects passing" number
     * hides the failure that matters: a detector that fires on everything scores full marks on
     * the MUST_FIRE half and zero on the other, and the total would look like a near miss.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that ran
     */
    static String recordingSummary(List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        long mustFire = Corpus.subjectsFor(lane).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE).count();
        long mustBeSilent = Corpus.subjectsFor(lane).size() - mustFire;
        long firedAsStated = Corpus.subjectsFor(lane).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_FIRE)
                .filter(s -> firedFor(findings, s)).count();
        long silentAsStated = Corpus.subjectsFor(lane).stream()
                .filter(s -> s.expectation() == RecordingSubject.Expectation.MUST_STAY_SILENT)
                .filter(s -> !firedFor(findings, s)).count();

        return "## Summary\n\n"
                + "| Measure | Value |\n|---|---|\n"
                + row("Lane", lane.propertyValue())
                + row("Detectors recorded to", Corpus.pairedDetectors(lane).size()
                      + " of " + DetectorTrust.DETECTOR_COUNT)
                + row("Detectors exposed at all", DetectorExposure.exposed(lane).size()
                      + " of " + DetectorTrust.DETECTOR_COUNT)
                + row("Subjects whose recorded calls oblige a finding", mustFire)
                + row("...that produced one", firedAsStated)
                + row("Subjects whose recorded calls are correct usage", mustBeSilent)
                + row("...that stayed silent", silentAsStated)
                + row("Total findings", findings.size())
                + "\n";
    }

    /**
     * {@return what a silent row drew from a detector other than its own, or nothing}
     *
     * <p>The per-subject table above filters each row to the detector it names, which is what the
     * row is a claim about. That filter used to hide the rest: a finding from any other detector
     * on a body the corpus writes down as correct appeared in no table and failed no gate.
     * {@code CorpusGates.noCollateralFindingOnASilentRow} fails the run on one, at a bar that
     * depends on the lane and is reasoned about on {@link CorpusLane#failsOnAnyCollateral()}.
     * Where that bar is the tier one, a finding below it is a question rather than a verdict and
     * the answer is a judgement about a body this module wrote, so it is printed here and left to
     * a reader instead of being asserted.
     *
     * <p>Structurally empty in the recording lane, whose bar is absolute: anything this method
     * would print there fails the run first, which is why the sentence it prints says so. The one
     * entry the agent-pair lane prints is discussed on the gate.
     *
     * @param findings what the detectors reported
     * @param lane     the lane that produced them
     */
    private static String collateral(List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        List<CorpusRecorder.Finding> collateral =
                CorpusGates.collateralOnSilentRows(findings, lane);
        if (collateral.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        out.append("\n## Collateral findings on silent rows\n\n")
                .append("A row below states that one named detector stays silent, and another ")
                .append("detector reported on the same body. Each is under the bar this lane ")
                .append("fails on, or the run would have stopped. ")
                .append(lane.failsOnAnyCollateral()
                        ? "That bar is absolute here, so this section is empty by "
                                + "construction and its presence is a defect in the gate."
                        : "That bar is VERDICT tier at HIGH or CRITICAL severity, so each "
                                + "entry is a remark about a body whose row only ever "
                                + "claimed its own hazard is absent.")
                .append("\n\n")
                .append("| Subject | Its row's detector | Reported by | Tier / severity |\n")
                .append("|---|---|---|---|\n");
        for (CorpusRecorder.Finding finding : collateral) {
            RecordingSubject subject = Corpus.pairByTestMethod(lane, finding.subject());
            out.append("| `").append(finding.subject()).append("` | `")
                    .append(DetectorExposure.classOf(subject.detector())).append("` | `")
                    .append(finding.detector()).append("` | ")
                    .append(finding.tier()).append(" / ").append(finding.severity())
                    .append(" |\n");
        }
        return out.toString();
    }

    /** {@return whether {@code subject}'s own detector reported on it} */
    private static boolean firedFor(List<CorpusRecorder.Finding> findings, RecordingSubject subject) {
        String detectorClass = DetectorExposure.classOf(subject.detector());
        return findings.stream()
                .anyMatch(finding -> finding.subject().equals(subject.testMethod())
                        && finding.detector().equals(detectorClass));
    }

    /**
     * The exposure section: what this lane could have fed, per feed and per detector.
     *
     * <p>The per-detector rows are the ones an evaluating team asks for. A zero in the "with a
     * finding" column of an exposed detector is a measured zero over a stated denominator; the
     * detectors that are not exposed are counted once, in the feed table, and never given a rate.
     */
    static String exposure(List<CorpusRecorder.Finding> findings, CorpusLane lane) {
        long safeSubjects = Corpus.count(Contract.THREAD_SAFE);
        long unsafeSubjects = Corpus.count(Contract.NOT_THREAD_SAFE);
        long subjects = safeSubjects + unsafeSubjects;

        StringBuilder out = new StringBuilder("## Detector exposure\n\n")
                .append("Exposure is what this lane could have fed a detector. It is the denominator ")
                .append("every rate below is measured over: without it a zero from a detector that ")
                .append("never ran reads the same as a zero from one that looked.\n\n")
                .append("| Feed | Detectors | Exposed in this lane | Subject-detector pairs exposed | Pairs with a finding |\n")
                .append("|---|---:|---:|---:|---:|\n");

        long exposedTotal = 0;
        long pairsTotal = 0;
        long firedPairsTotal = 0;
        for (DetectorFeed feed : DetectorFeed.values()) {
            Set<DetectorType> ofFeed = DetectorExposure.fedBy(feed);
            long exposed = ofFeed.stream().filter(type -> DetectorExposure.isExposed(type, lane)).count();
            long pairs = exposed * subjects;
            long fired = firedPairs(findings, feed);
            exposedTotal += exposed;
            pairsTotal += pairs;
            firedPairsTotal += fired;
            out.append("| ").append(feed).append(" | ").append(ofFeed.size()).append(" | ")
                    .append(exposed).append(" | ").append(pairs).append(" | ").append(fired).append(" |\n");
        }
        out.append("| **Total** | **").append(DetectorTrust.DETECTOR_COUNT).append("** | **")
                .append(exposedTotal).append("** | **").append(pairsTotal).append("** | **")
                .append(firedPairsTotal).append("** |\n\n");

        out.append("Per exposed detector, over ").append(safeSubjects)
                .append(" documented-thread-safe and ").append(unsafeSubjects)
                .append(" documented-not-thread-safe subjects:\n\n")
                .append("| Detector | Feed | Safe subjects exposed | ...with a finding | Unsafe subjects exposed | ...with a finding |\n")
                .append("|---|---|---:|---:|---:|---:|\n");

        for (DetectorType type : DetectorExposure.exposed(lane)) {
            String detectorClass = DetectorExposure.classOf(type);
            out.append("| `").append(detectorClass).append("` | ")
                    .append(DetectorExposure.feedOf(type)).append(" | ")
                    .append(safeSubjects).append(" | ")
                    .append(subjectsWithFinding(findings, detectorClass, Contract.THREAD_SAFE)).append(" | ")
                    .append(unsafeSubjects).append(" | ")
                    .append(subjectsWithFinding(findings, detectorClass, Contract.NOT_THREAD_SAFE)).append(" |\n");
        }
        return out.toString();
    }

    private static long firedPairs(List<CorpusRecorder.Finding> findings, DetectorFeed feed) {
        return findings.stream()
                .filter(finding -> DetectorExposure.typeOf(finding.detector())
                        .map(type -> DetectorExposure.feedOf(type) == feed)
                        .orElse(false))
                .map(finding -> finding.detector() + "@" + finding.subject())
                .distinct()
                .count();
    }

    private static long subjectsWithFinding(List<CorpusRecorder.Finding> findings,
                                            String detectorClass,
                                            Contract contract) {
        return findings.stream()
                .filter(finding -> finding.detector().equals(detectorClass))
                .filter(finding -> contractOf(finding.subject()) == contract)
                .map(CorpusRecorder.Finding::subject)
                .distinct()
                .count();
    }

    static String summary(List<CorpusRecorder.Finding> findings,
                          List<CorpusRecorder.Crash> crashes,
                          CorpusLane lane) {
        Set<String> safeWithAnyFinding = findings.stream()
                .filter(finding -> contractOf(finding.subject()) == Contract.THREAD_SAFE)
                .map(CorpusRecorder.Finding::subject)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> safeWithFalsePositive = findings.stream()
                .filter(finding -> {
                    Subject subject = Corpus.byTestMethod(finding.subject());
                    return subject != null && isFalsePositive(finding, subject);
                })
                .map(CorpusRecorder.Finding::subject)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> unsafeWithAnyFinding = findings.stream()
                .filter(finding -> contractOf(finding.subject()) == Contract.NOT_THREAD_SAFE)
                .map(CorpusRecorder.Finding::subject)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> unsafeWithCrash = crashes.stream()
                .filter(crash -> contractOf(crash.subject()) == Contract.NOT_THREAD_SAFE)
                .map(CorpusRecorder.Crash::subject)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return "## Summary\n\n"
                + "| Measure | Value |\n|---|---|\n"
                + row("Lane", lane.propertyValue())
                + row("Detectors exposed at all", DetectorExposure.exposed(lane).size()
                      + " of " + DetectorTrust.DETECTOR_COUNT)
                + row("Documented thread-safe subjects", Corpus.count(Contract.THREAD_SAFE))
                + row("...with any finding at all", safeWithAnyFinding.size())
                + row("...with a VERDICT-tier HIGH or CRITICAL finding (false positives)",
                      safeWithFalsePositive.size())
                + row("Documented not-thread-safe subjects", Corpus.count(Contract.NOT_THREAD_SAFE))
                + row("...with at least one finding", unsafeWithAnyFinding.size())
                + row("...that threw out of the library's own code", unsafeWithCrash.size())
                + row("Total findings", findings.size())
                + "\n"
                + noiseDetail(findings)
                + (unsafeWithAnyFinding.isEmpty() ? ""
                    : "Detected not-thread-safe subjects: " + unsafeWithAnyFinding + "\n");
    }

    /**
     * Every finding on a documented-thread-safe subject, with what the detector actually said.
     *
     * <p>The noise column is the number this eval exists to hold down, and a bare count of it is
     * not reviewable. Printing the message means a reader can decide for themselves whether a
     * finding is a model gap or a contract the javadoc oversold, without re-running anything.
     */
    private static String noiseDetail(List<CorpusRecorder.Finding> findings) {
        List<CorpusRecorder.Finding> noise = findings.stream()
                .filter(finding -> contractOf(finding.subject()) == Contract.THREAD_SAFE)
                .toList();
        if (noise.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("### Findings on documented-thread-safe subjects\n\n")
                .append("| Subject | Detector | Tier/severity | Evidence |\n|---|---|---|---|\n");
        for (CorpusRecorder.Finding finding : noise) {
            out.append("| `").append(finding.subject()).append("` | ")
                    .append(finding.detector()).append(" | ")
                    .append(finding.tier()).append('/').append(finding.severity()).append(" | ")
                    .append(finding.evidence().replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        return out.append('\n').toString();
    }

    /**
     * {@return the distinct severities of {@code findings}, in severity order, joined by '/'}
     *
     * <p>Printed next to a firing row's count so that a run is the record a row's
     * {@code expectedSeverity} is pinned from (#660).
     */
    static String severitiesOf(List<CorpusRecorder.Finding> findings) {
        return findings.stream()
                .map(CorpusRecorder.Finding::severity)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String row(String label, long value) {
        return "| " + label + " | " + value + " |\n";
    }

    private static String row(String label, String value) {
        return "| " + label + " | " + value + " |\n";
    }

    private static Contract contractOf(String testMethod) {
        Subject subject = Corpus.byTestMethod(testMethod);
        return subject == null ? null : subject.contract();
    }
}
