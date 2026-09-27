package se.deversity.asynctest.spi.adapters.fixture;

/**
 * Hands out a detector whose own class is not public, the way a detector nested in a user's test
 * class often is (#851); its report method and report are public.
 */
public final class HiddenDetectorClass {

    private HiddenDetectorClass() {
    }

    /** {@return a new detector of a class only this package may call into} */
    public static Object create() {
        return new Detector();
    }

    /** Not public, so a reflective {@code analyze()} from another package needs the package open. */
    static final class Detector {
        public Report analyze() {
            return new Report();
        }
    }

    /** A public report that always has issues. */
    public static final class Report {
        /** {@return always {@code true}, so a reachable report comes out as one finding} */
        public boolean hasIssues() {
            return true;
        }

        @Override
        public String toString() {
            return "[MEDIUM] a finding from a detector class that is not public";
        }
    }
}
