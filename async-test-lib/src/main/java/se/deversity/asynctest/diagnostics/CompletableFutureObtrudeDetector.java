package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
public final class CompletableFutureObtrudeDetector {

    private static final class State {
        final String label;
        final int obtrudeCount;
        final String lastObtrudedByThread;

        State(String label, int obtrudeCount, String threadName) {
            this.label = label;
            this.obtrudeCount = obtrudeCount;
            this.lastObtrudedByThread = threadName;
        }
    }

    private final Map<Integer, State> obtrudes = new ConcurrentHashMap<>();

    /**
     * Record an obtrude action on a CompletableFuture.
     */
    public void recordObtrude(CompletableFuture<?> future, String label, Thread thread) {
        if (future == null || thread == null) return;
        int id = System.identityHashCode(future);
        String name = label != null ? label : "CompletableFuture@" + id;
        obtrudes.merge(id, new State(name, 1, thread.getName()), (old, val) -> 
            new State(name, old.obtrudeCount + 1, val.lastObtrudedByThread)
        );
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : obtrudes.values()) {
            String msg = String.format(
                "CompletableFuture '%s' obtruded %d times (last by thread '%s') — obtruding values or exceptions forces downstream pipelines to execute with outdated/inconsistent states, introducing publication races.",
                s.label, s.obtrudeCount, s.lastObtrudedByThread
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
        return r;
    }

    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

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
