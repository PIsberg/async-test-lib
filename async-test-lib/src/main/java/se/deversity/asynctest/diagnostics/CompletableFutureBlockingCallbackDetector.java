package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * One thread's open callbacks, innermost first, and the round they were opened in. A stack,
     * not a slot: a callback that completes another future runs that future's dependent inline on
     * the same thread, and the inner exit must not end the outer callback (#941).
     */
    private static final class OpenCallbacks {
        final ArrayDeque<String> names = new ArrayDeque<>();
        long round;
    }

    private final ThreadLocal<OpenCallbacks> openCallbacks = ThreadLocal.withInitial(OpenCallbacks::new);
    /** Advanced at each round start; a thread's stack from an earlier round is discarded on use. */
    private final AtomicLong round = new AtomicLong();
    private final Map<String, State> violations = new ConcurrentHashMap<>();

    /** {@return the calling thread's open callbacks, emptied first if an earlier round opened them} */
    private OpenCallbacks open() {
        OpenCallbacks open = openCallbacks.get();
        long now = round.get();
        if (open.round != now) {
            open.names.clear();
            open.round = now;
        }
        return open;
    }

    /**
     * Record entry into a CompletableFuture callback.
     *
     * @param callbackName a label identifying the callback in the report
     * @param thread the thread performing the operation
     */
    public void recordEnterCallback(String callbackName, Thread thread) {
        if (thread == null) return;
        open().names.push(Objects.requireNonNullElse(callbackName, "callback"));
    }

    /**
     * Record exit from a CompletableFuture callback.
     *
     * @param thread the thread performing the operation
     */
    public void recordExitCallback(Thread thread) {
        if (thread == null) return;
        open().names.pollFirst();
    }

    /**
     * Record a blocking call executed on a thread.
     *
     * @param thread the thread performing the operation
     * @param blockingApiName the blocking API that was called, as it should appear in the report
     */
    public void recordBlockingCall(Thread thread, String blockingApiName) {
        if (thread == null) return;
        String currentCallback = open().names.peekFirst();
        if (currentCallback != null) {
            State s = violations.computeIfAbsent(currentCallback, k -> new State(currentCallback));
            s.blockingCalls.add(blockingApiName + " by thread " + thread.getName());
        }
    }
    /**
     * Ends the round's callbacks. A callback that threw before its exit leaves its pool thread
     * inside it, and a later round's blocking call on that thread would be reported against a
     * callback that is long gone (#941). The runner calls this once the previous round's workers
     * have finished; each thread drops its stale stack the next time it records.
     */
    public void markInvocationStart() {
        round.incrementAndGet();
    }

    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
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
