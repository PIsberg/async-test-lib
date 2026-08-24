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
 * in this corpus 137 of the 142 are never fed at all, so the exposure section is not decoration.
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
