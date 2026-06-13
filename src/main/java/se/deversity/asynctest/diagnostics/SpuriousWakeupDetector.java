package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects wait() or Condition.await() calls invoked outside of a while loop condition check,
 * exposing the thread to spurious wakeups.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap stores state per monitor instance.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java"
)
public final class SpuriousWakeupDetector {

    private static final class State {
        final String monitorName;
        final Set<String> threadsOutsideLoop = ConcurrentHashMap.newKeySet();

        State(String monitorName) {
            this.monitorName = monitorName;
        }
    }

    private final Map<Integer, State> monitors = new ConcurrentHashMap<>();

    /**
     * Record a wait/await operation.
     */
    public void recordWait(Object monitor, String monitorName, boolean insideLoop, Thread thread) {
        if (monitor == null || thread == null) return;
        if (insideLoop) return; // Safely inside loop, do not track as violation
        
        int id = System.identityHashCode(monitor);
        State s = monitors.computeIfAbsent(id, k -> new State(
            monitorName != null ? monitorName : "Monitor@" + id
        ));
        s.threadsOutsideLoop.add(thread.getName());
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : monitors.values()) {
            if (s.threadsOutsideLoop.isEmpty()) continue;
            
            String msg = String.format(
                "Monitor '%s' wait() called outside a condition-checking loop by threads %s. This exposes the application to spurious wakeups where a thread wakes up and proceeds without the target condition being satisfied.",
                s.monitorName, String.join(", ", s.threadsOutsideLoop)
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "SpuriousWakeup",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "monitorName", s.monitorName,
                    "threadsOutsideLoop", List.copyOf(s.threadsOutsideLoop)
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
            if (violations.isEmpty()) return "SPURIOUS WAKEUP HAZARD — clean";
            StringBuilder sb = new StringBuilder("SPURIOUS WAKEUP HAZARD DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Always invoke wait() or Condition.await() inside a while loop checking the predicate:\n")
              .append("      synchronized(lock) { while (!condition) { lock.wait(); } }\n");
            return sb.toString();
        }
    }
}
