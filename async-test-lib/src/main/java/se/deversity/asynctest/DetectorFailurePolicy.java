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
     * <p>The line ends with the frames the failure was thrown from. Outside strict mode it is
     * often all a build log keeps: the run stays green and nobody reruns it, and an exception's
     * message alone rarely names a class. {@code ArrayIndexOutOfBoundsException: Index 1 out of
     * bounds for length 1} reached the corpus eval twice that way before anyone could say which
     * detector method threw it (#605).
     *
     * @param detectorName simple class name of the detector or report that failed
     * @param failure      what it threw
     * @throws AssertionError under strict mode, always
     */
    public static void detectorFailed(String detectorName, Throwable failure) {
        System.err.println("[AsyncTest] Detector " + detectorName
            + " failed during analysis and was skipped: " + failure + thrownAt(failure));
        if (Boolean.getBoolean(STRICT_PROPERTY)) {
            throw new AssertionError("Detector " + detectorName + " threw during analysis, so its"
                + " finding was lost and the run reported nothing for it — which looks exactly"
                + " like a clean run. Strict mode (" + STRICT_PROPERTY + ") fails the build"
                + " instead of writing a line to stderr.", failure);
        }
    }

    /** How many frames of the failure the diagnostic line carries. */
    private static final int FRAMES_SHOWN = 3;

    /**
     * {@return {@code " at frame <- caller <- caller"} for the top of {@code failure}'s stack, or
     * an empty string when it carries none}
     *
     * <p>Three frames name the method that threw and the path that reached it while keeping the
     * diagnostic to one line.
     */
    private static String thrownAt(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames.length == 0) {
            return "";
        }
        StringBuilder at = new StringBuilder(" at ");
        int shown = Math.min(FRAMES_SHOWN, frames.length);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                at.append(" <- ");
            }
            at.append(frames[i]);
        }
        return at.toString();
    }
}
