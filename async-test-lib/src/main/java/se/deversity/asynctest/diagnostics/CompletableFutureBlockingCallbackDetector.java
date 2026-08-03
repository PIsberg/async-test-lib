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
 * Detects blocking calls (like get(), join(), sleep()) inside CompletableFuture callback pipelines,
 * which can cause pool thread starvation or deadlocks.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ThreadLocal tracks active callbacks; ConcurrentHashMap stores violations.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/CompletableFutureBlockingCallbackDetectorTest.java"
)
public final class CompletableFutureBlockingCallbackDetector {

    private static final class State {
        final String callbackName;
        final Set<String> blockingCalls = ConcurrentHashMap.newKeySet();

        State(String callbackName) {
            this.callbackName = callbackName;
        }
    }

    private final ThreadLocal<String> activeCallback = new ThreadLocal<>();
    private final Map<String, State> violations = new ConcurrentHashMap<>();

    /**
     * Record entry into a CompletableFuture callback.
     */
    public void recordEnterCallback(String callbackName, Thread thread) {
        if (thread == null) return;
        activeCallback.set(callbackName);
    }

    /**
     * Record exit from a CompletableFuture callback.
     */
    public void recordExitCallback(Thread thread) {
        if (thread == null) return;
        activeCallback.remove();
    }

    /**
     * Record a blocking call executed on a thread.
     */
    public void recordBlockingCall(Thread thread, String blockingApiName) {
        if (thread == null) return;
        String currentCallback = activeCallback.get();
        if (currentCallback != null) {
            State s = violations.computeIfAbsent(currentCallback, k -> new State(currentCallback));
            s.blockingCalls.add(blockingApiName + " by thread " + thread.getName());
        }
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : violations.values()) {
            String msg = String.format(
                "CompletableFuture callback '%s' invoked blocking operations: %s. Blocking inside CompletableFuture callbacks exhausts pool threads and can cause deadlocks.",
                s.callbackName, String.join(", ", s.blockingCalls)
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "CompletableFutureBlockingCallback",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "callbackName", s.callbackName,
                    "blockingCalls", List.copyOf(s.blockingCalls)
                ),
                Instant.now()
            ));
        }
        return r;
    }

    public static final class Report {
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "COMPLETABLE FUTURE BLOCKING CALLBACK — clean";
            StringBuilder sb = new StringBuilder("COMPLETABLE FUTURE BLOCKING CALLBACK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Avoid blocking calls like get(), join(), sleep(), or synchronized locks inside CompletableFuture callbacks.\n")
              .append("    - Compose asynchronous stages instead using thenCompose(), thenComposeAsync(), etc.\n");
            return sb.toString();
        }
    }
}
