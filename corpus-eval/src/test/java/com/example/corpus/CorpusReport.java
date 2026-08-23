package com.example.corpus;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

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
 * decides anything: the gates live in {@link CorpusEvalTest}.
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
                      int invocations) {
        StringBuilder out = new StringBuilder();
        out.append("# Corpus eval run\n\n")
                .append("- JVM: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vm.name")).append(")\n")
                .append("- OS: ").append(System.getProperty("os.name")).append('\n')
                .append("- Configuration: threads=").append(threads)
                .append(", invocations=").append(invocations)
                .append(", detectAll=true, agent=fields=true\n")
                .append("- Body executions: ").append(CorpusRecorder.bodyExecutions()).append("\n\n");

        out.append("## Per subject\n\n")
                .append("| Subject | Contract | Findings | Detectors (tier/severity) | Crashes |\n")
                .append("|---|---|---:|---|---:|\n");

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
                    .append(subject.contract()).append(" | ")
                    .append(mine.size()).append(" | ")
                    .append(detectors.isEmpty() ? "-" : String.join(", ", detectors)).append(" | ")
                    .append(crashCount).append(" |\n");
        }

        out.append('\n').append(summary(findings, crashes));

        Path target = Path.of("target", "corpus-eval", "corpus-eval.md");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the corpus report", e);
        }
        return target;
    }

    static String summary(List<CorpusRecorder.Finding> findings, List<CorpusRecorder.Crash> crashes) {
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
                + row("Documented thread-safe subjects", Corpus.count(Contract.THREAD_SAFE))
                + row("...with any finding at all", safeWithAnyFinding.size())
                + row("...with a VERDICT-tier HIGH or CRITICAL finding (false positives)",
                      safeWithFalsePositive.size())
                + row("Documented not-thread-safe subjects", Corpus.count(Contract.NOT_THREAD_SAFE))
                + row("...with at least one finding", unsafeWithAnyFinding.size())
                + row("...that threw out of the library's own code", unsafeWithCrash.size())
                + row("Total findings", findings.size())
                + "\n"
                + (safeWithAnyFinding.isEmpty() ? ""
                    : "Findings on documented-thread-safe subjects: " + safeWithAnyFinding + "\n")
                + (unsafeWithAnyFinding.isEmpty() ? ""
                    : "Detected not-thread-safe subjects: " + unsafeWithAnyFinding + "\n");
    }

    private static String row(String label, long value) {
        return "| " + label + " | " + value + " |\n";
    }

    private static Contract contractOf(String testMethod) {
        Subject subject = Corpus.byTestMethod(testMethod);
        return subject == null ? null : subject.contract();
    }
}
