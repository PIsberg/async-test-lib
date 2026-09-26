package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what an analysis pass produced: one report per detector, and the grades of the
 * individual findings inside it when the detector graded them.
 *
 * <p>Exists so {@code DetectorRegistry.ifIssue} can carry per-finding grades without every one of
 * its call sites changing. The reports map is what it always was, keyed by the detector class
 * simple name; the grades map is populated only for reports implementing {@link GradedFindings}.
 *
 * <p>Not thread-safe, and does not need to be: an analysis pass runs on the runner thread after
 * every worker for the round has finished, which is the same guarantee
 * {@code AsyncTestContext.analyzeAll()} documents.
 */
final class FindingSink {

    private final Map<String, String> reports = new LinkedHashMap<>();
    private final Map<String, List<GradedFindings.Grade>> grades = new LinkedHashMap<>();
    private final Map<String, IssueSeverity> severities = new LinkedHashMap<>();

    /**
     * Records one detector's report, and its per-finding grades when it has any.
     *
     * <p>Reports merge the way they always did, because one detector can be asked to analyze more
     * than once in a run. Grades accumulate alongside so a merged report keeps every finding's
     * grade rather than the last pass overwriting the earlier one.
     */
    void add(String detectorName, String report, @Nullable List<GradedFindings.Grade> findingGrades) {
        add(detectorName, report, findingGrades, null);
    }

    /**
     * Records one detector's report with the most severe severity among its structured findings,
     * when it has any. A merged report keeps the more severe of the passes' severities.
     */
    void add(String detectorName, String report, @Nullable List<GradedFindings.Grade> findingGrades,
             @Nullable IssueSeverity structuredSeverity) {
        reports.merge(detectorName, report, (first, second) -> first + "\n" + second);
        if (findingGrades != null && !findingGrades.isEmpty()) {
            grades.computeIfAbsent(detectorName, name -> new ArrayList<>()).addAll(findingGrades);
        }
        if (structuredSeverity != null) {
            severities.merge(detectorName, structuredSeverity,
                    (first, second) -> first.compareTo(second) <= 0 ? first : second);
        }
    }

    /** {@return the reports, keyed by detector name} */
    Map<String, String> reports() {
        return reports;
    }

    /** {@return the graded findings, keyed by detector name; absent for ungraded detectors} */
    Map<String, List<GradedFindings.Grade>> grades() {
        return grades;
    }

    /** {@return the structured severities, keyed by detector name; absent where a report has none} */
    Map<String, IssueSeverity> severities() {
        return severities;
    }
}
