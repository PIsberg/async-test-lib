package se.deversity.asynctest.corpus;

import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects every finding the detectors report and attributes it to the subject being exercised.
 *
 * <p>The library's listener events carry no test identity, so {@link SubjectTracking} sets the
 * current subject around each test method and this recorder stamps it onto the violation. Tests
 * run sequentially in this module, which is what makes a single current-subject field correct.
 */
final class CorpusRecorder implements AsyncTestListener {

    /** One reported finding, already resolved to the reporting detector's trust tier. */
    record Finding(String subject, String detector, IssueSeverity severity, TrustTier tier, String message) {
    }

    /** A RuntimeException thrown out of a subject's own code while several threads used it. */
    record Crash(String subject, String exception) {
    }

    private static final CorpusRecorder INSTANCE = new CorpusRecorder();
    private static final List<Finding> FINDINGS = new CopyOnWriteArrayList<>();
    private static final List<Crash> CRASHES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    private static volatile String currentSubject = "unattributed";

    private CorpusRecorder() {
    }

    static void install() {
        AsyncTestListenerRegistry.register(INSTANCE);
    }

    static void uninstall() {
        AsyncTestListenerRegistry.unregister(INSTANCE);
    }

    static void currentSubject(String subject) {
        currentSubject = subject;
    }

    static void recordCrash(RuntimeException thrown) {
        CRASHES.add(new Crash(currentSubject, thrown.getClass().getName()));
    }

    static void countBodyExecution() {
        BODY_EXECUTIONS.incrementAndGet();
    }

    static List<Finding> findings() {
        return List.copyOf(FINDINGS);
    }

    static List<Crash> crashes() {
        return List.copyOf(CRASHES);
    }

    static int bodyExecutions() {
        return BODY_EXECUTIONS.get();
    }

    @Override
    public void onViolation(Violation violation) {
        FINDINGS.add(new Finding(
                currentSubject,
                violation.detector(),
                violation.severity(),
                DetectorTrust.tierOfDetector(violation.detector()),
                violation.message()));
    }
}
