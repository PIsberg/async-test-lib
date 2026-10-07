package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Detects CompletableFuture.obtrudeValue() or obtrudeException() calls which
 * bypass normal completion pipelines and trigger race conditions or state inconsistency.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap stores state per CF instance.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureObtrudeDetectorTest.java"
)
public final class CompletableFutureObtrudeDetector extends AbstractInstanceDetector<CompletableFutureObtrudeDetector.State> {

    static final class State {
        final String label;
        final AtomicInteger obtrudeCount = new AtomicInteger();
        volatile @Nullable String lastObtrudedByThread;

        State(String label) {
            this.label = label;
        }
    }

    @Override
    State newState(Object instance, String label) {
        return new State(label);
    }

    /**
     * Record an obtrude action on a CompletableFuture.
     *
     * @param future the future being recorded, tracked by identity
     * @param label a label identifying it in the report
     * @param thread the thread performing the operation
     */
    public void recordObtrude(CompletableFuture<?> future, String label, Thread thread) {
        if (future == null || thread == null) return;
        State s = stateFor(future, label, "CompletableFuture");
        s.obtrudeCount.incrementAndGet();
        s.lastObtrudedByThread = thread.getName();
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : states()) {
            String msg = String.format(
                "CompletableFuture '%s' obtruded %d times (last by thread '%s') — obtruding values or exceptions forces downstream pipelines to execute with outdated/inconsistent states, introducing publication races.",
                s.label, s.obtrudeCount.get(), s.lastObtrudedByThread
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "CompletableFutureObtrude",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "label", s.label,
                    "obtrudeCount", s.obtrudeCount,
                    "lastObtrudedByThread", s.lastObtrudedByThread
                ),
                Instant.now()
            ));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "COMPLETABLE FUTURE OBTRUDE — clean";
            StringBuilder sb = new StringBuilder("COMPLETABLE FUTURE OBTRUDE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Banish use of obtrudeValue() and obtrudeException() in application pipelines.\n")
              .append("    - Use complete() or completeExceptionally() for cooperative, race-free publication.\n");
            return sb.toString();
        }
    }
}
