package se.deversity.asynctest.spi.adapters.fixture;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.Map;

/**
 * A detector whose report answers {@code hasIssues()} through a public interface while its
 * {@code structuredViolations} list sits on a type that is not public (#851).
 *
 * <p>The structured finding is {@code MEDIUM} and the text says {@code [HIGH]}, so which of the two
 * a caller got shows whether the list was read.
 */
public final class HiddenStructuredReportDetector {

    /** {@return a report that has issues, one {@code MEDIUM} finding in its list} */
    public Findings analyze() {
        return new Report();
    }

    /** What any caller may ask of the report. */
    public interface Findings {
        /** {@return whether the report holds any finding} */
        boolean hasIssues();
    }

    /** Not public, so reading its public list from another package needs the package open. */
    static final class Report implements Findings {
        public final List<Violation> structuredViolations = List.of(new Violation("HiddenStructured",
                IssueSeverity.MEDIUM, "the finding at the severity the detector chose", List.of(), Map.of(), null));

        @Override
        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "[HIGH] the same finding written as text";
        }
    }
}
