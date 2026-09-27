package se.deversity.asynctest.spi.adapters.fixture;

/**
 * A detector in the canonical shape whose report type is not public (#847, #851).
 *
 * <p>It lives in its own package because only there is the report type out of the adapter's
 * plain reach: in {@code spi.adapters} itself a package-private class would be accessible to it.
 * From the class path the adapter still reads it, since an unnamed module is open to every other
 * one; loaded into a named module that exports this package without opening it, it cannot.
 */
public final class HiddenReportDetector {

    /** {@return a report that has issues, of a type only this package may call into} */
    public Report analyze() {
        return new Report();
    }

    /** Not public, so a reflective {@code hasIssues()} from another package needs the package open. */
    static final class Report {
        /** {@return always {@code true}, so a reachable report comes out as one finding} */
        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "[HIGH] a finding behind a report type that is not public";
        }
    }
}
