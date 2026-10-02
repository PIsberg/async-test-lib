package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;

import java.lang.reflect.Method;
import java.util.List;

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
 * <p>The same switch holds the built-in reports to their own structured findings: a report type
 * that keeps a {@code structuredViolations} list and has issues with that list empty fails the build
 * under it, and changes nothing outside it ({@link #structuredFindingsMissing}), checked where the
 * detector builds the report ({@link #checkedReport}) and again where the registry takes it in.
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

    /**
     * Reports a built-in report that has issues but put none of them in its structured list.
     *
     * <p>A report type that keeps a public {@code structuredViolations} list promises the
     * {@code failOn} gate the severity of each finding. A report with issues and an empty list
     * breaks that promise for this finding: the gate falls back to guessing from the text, which
     * can land on a different severity than the detector chose on its other paths (#774). A
     * hand-kept list of drivers found the paths known in #774 and cannot find one added later, so
     * the check runs where every report enters the findings (#802) and where every structured
     * detector builds one ({@link #checkedReport}, #829), and every test in this build that makes a
     * detector fire is a driver for it.
     *
     * <p>Only under {@value #STRICT_PROPERTY}. Everywhere else it returns at once and writes
     * nothing: the finding is still reported with its text, and a consumer's build has no use for
     * a line about the library's own bookkeeping. A report type with no such field is judged by
     * its text by design and is never held to it.
     *
     * @param detectorName simple class name of the detector that produced the report
     * @param report       the report, which has issues and carries no structured finding
     * @throws AssertionError under strict mode, when the report's type keeps the list
     * @since 1.12.3
     */
    public static void structuredFindingsMissing(String detectorName, Object report) {
        if (!Boolean.getBoolean(STRICT_PROPERTY) || !keepsStructuredFindings(report)) {
            return;
        }
        throw new AssertionError("Detector " + detectorName + " reported issues with an empty "
            + DetectorDefaultSeverity.STRUCTURED_FIELD
            + " list, so the failOn gate reads this finding's severity from its text instead of"
            + " the one the detector states on its other paths. Add a Violation beside the text"
            + " line, at the severity the text resolves to. Strict mode (" + STRICT_PROPERTY
            + ") fails the build; consumers see the finding unchanged. Report: " + report);
    }

    /** Report types already named by {@link #structuredFindingsUnreadable}; a ClassValue retains no loader. */
    private static final ClassValue<java.util.concurrent.atomic.AtomicBoolean> UNREADABLE_SAID =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.atomic.AtomicBoolean computeValue(Class<?> type) {
                    return new java.util.concurrent.atomic.AtomicBoolean();
                }
            };

    /**
     * Says, once per report type, that a report's {@code structuredViolations} list could not be
     * read, so its findings were graded from their text (#859).
     *
     * <p>A third-party report whose list sits on a type in a package not open to this library still
     * reports: the finding comes out of its text (#851). But the severities its author chose are not
     * the ones the {@code failOn} gate sees, and without this line nothing said so. Written in every
     * mode and never thrown: the library cannot tell a deliberate closed module from an oversight,
     * and the finding itself is not lost.
     *
     * @param detectorName simple class name of the detector that produced the report
     * @param reportType   the report's class, whose list was refused
     * @since 1.12.4
     */
    public static void structuredFindingsUnreadable(String detectorName, Class<?> reportType) {
        if (UNREADABLE_SAID.get(reportType).compareAndSet(false, true)) {
            System.err.println("[AsyncTest] Detector " + detectorName + " keeps a "
                + DetectorDefaultSeverity.STRUCTURED_FIELD + " list on " + reportType.getName()
                + " that this library may not read, because its package is not open to it; its"
                + " findings are graded from their text instead of the severities it states. Open"
                + " the package to the library's module to have them read.");
        }
    }

    /**
     * Returns the report a detector has just built, after holding it to its own structured findings.
     *
     * <p>{@link #structuredFindingsMissing} runs where the registry takes a report in, so on its own
     * it saw only the detectors some test fires through the registry, a minority of those that keep
     * the list (#829). A detector's own unit tests call {@code analyze()} and read the report
     * directly, and so does the no-context {@code Phase1DetectorSet.printReports()}. Every
     * report-building method of a detector that keeps the list returns through this, so any caller
     * that obtains a report with issues is a driver, whichever path produced the finding.
     *
     * <p>Only under {@value #STRICT_PROPERTY}. Everywhere else it returns {@code report} without
     * reading it, and nothing about the report changes under strict mode either.
     *
     * @param detector the detector that built the report; the failure names its simple class name
     * @param report   the report about to be returned, whose type keeps a public
     *                 {@code structuredViolations} list and answers {@code hasIssues()}
     * @param <R>      the report type
     * @return {@code report}, the same instance
     * @throws AssertionError under strict mode, when the report has issues and its list holds no
     *                        {@code Violation}
     * @since 1.12.3
     */
    public static <R> R checkedReport(Object detector, R report) {
        if (Boolean.getBoolean(STRICT_PROPERTY) && keepsStructuredFindings(report)
                && hasIssues(report) && DetectorDefaultSeverity.structuredIn(report).isEmpty()) {
            structuredFindingsMissing(detector.getClass().getSimpleName(), report);
        }
        return report;
    }

    /** {@return what {@code report}'s public {@code hasIssues()} answers} */
    private static boolean hasIssues(Object report) {
        try {
            Method answer = report.getClass().getMethod("hasIssues");
            answer.trySetAccessible();
            return Boolean.TRUE.equals(answer.invoke(report));
        } catch (ReflectiveOperationException unreadable) {
            throw new AssertionError("Report " + report.getClass().getName() + " keeps "
                + DetectorDefaultSeverity.STRUCTURED_FIELD + " but has no callable hasIssues(), so"
                + " strict mode cannot tell whether it has findings", unreadable);
        }
    }

    /** {@return whether {@code report}'s type declares the public structured list} */
    private static boolean keepsStructuredFindings(Object report) {
        try {
            return List.class.isAssignableFrom(report.getClass()
                .getField(DetectorDefaultSeverity.STRUCTURED_FIELD)
                .getType());
        } catch (NoSuchFieldException textOnly) {
            return false;
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
