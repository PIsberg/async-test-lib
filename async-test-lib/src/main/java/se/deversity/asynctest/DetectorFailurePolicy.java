package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

/**
 * What happens when a detector throws while the runner is collecting its findings.
 *
 * <p>Both analysis sweeps — {@link DetectorRegistry#analyzeAll()} for the built-in detectors and
 * {@code spi.DetectorRegistry.analyzeAll()} for SPI ones — catch around each detector so that one
 * failure cannot discard the findings already collected or skip every detector after it. That is
 * the right behaviour in a consumer's build: a broken detector should cost its own finding, not
 * the whole run.
 *
 * <p>It is the wrong behaviour in <em>this</em> project's build. A detector that throws reports
 * nothing, and nothing reporting is indistinguishable from a clean run — so a detector can be
 * completely broken and the suite still passes. That is not hypothetical: five detectors shipped
 * for several releases dereferencing a registry miss inside {@code toString()}, and the only trace
 * was one stderr line nobody read. NullAway eventually found them, but a nullness checker only
 * catches the nullness-shaped instances of this failure.
 *
 * <p>Setting {@value #STRICT_PROPERTY} to {@code true} turns that stderr line into a build failure.
 * The library's own Maven and Gradle test configurations set it, so a detector that throws during
 * analysis goes red here and stays a contained warning everywhere else.
 *
 * @since 1.7.0
 */
@API(status = Status.INTERNAL)
public final class DetectorFailurePolicy {

    /**
     * System property that promotes a swallowed detector failure to a thrown
     * {@link AssertionError}. Off unless set, so consumers keep the containment behaviour.
     */
    public static final String STRICT_PROPERTY = "async-test.strict-detectors";

    private DetectorFailurePolicy() {
    }

    /**
     * Reports a detector that threw while being analysed or rendered.
     *
     * <p>Always writes the diagnostic line. Under {@value #STRICT_PROPERTY} it then throws, which
     * does abort the rest of that sweep — acceptable, because the only reason to enable strict
     * mode is to fail a build that would otherwise pass while reporting nothing.
     *
     * @param detectorName simple class name of the detector or report that failed
     * @param failure      what it threw
     * @throws AssertionError under strict mode, always
     */
    public static void detectorFailed(String detectorName, Throwable failure) {
        System.err.println("[AsyncTest] Detector " + detectorName
            + " failed during analysis and was skipped: " + failure);
        if (Boolean.getBoolean(STRICT_PROPERTY)) {
            throw new AssertionError("Detector " + detectorName + " threw during analysis, so its"
                + " finding was lost and the run reported nothing for it — which looks exactly"
                + " like a clean run. Strict mode (" + STRICT_PROPERTY + ") fails the build"
                + " instead of writing a line to stderr.", failure);
        }
    }
}
