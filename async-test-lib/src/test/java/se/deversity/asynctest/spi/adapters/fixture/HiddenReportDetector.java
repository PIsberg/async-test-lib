package se.deversity.asynctest.spi.adapters.fixture;

/**
 * A detector in the canonical shape whose report {@code LegacyDetectorAdapter} cannot call into
 * (#847).
 *
 * <p>It lives in its own package because only there is the report type out of the adapter's
 * reach: in {@code spi.adapters} itself a package-private class would be accessible to it.
 */
public final class HiddenReportDetector {

    /** {@return a report that has issues, of a type only this package may call into} */
    public Report analyze() {
        return new Report();
    }

    /** Not public, so a reflective {@code hasIssues()} from another package is refused. */
    static final class Report {
        /** {@return always {@code true}, so a reachable report would come out as one finding} */
        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "[HIGH] a finding the adapter cannot read";
        }
    }
}
