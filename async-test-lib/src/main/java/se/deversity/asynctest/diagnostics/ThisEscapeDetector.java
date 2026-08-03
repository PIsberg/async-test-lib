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
 * Detects {@code this}-escape: a constructor publishing a reference to the
 * object being built before construction finishes.
 *
 * <p><strong>Why it matters.</strong> Until a constructor returns, the object's
 * {@code final} fields are not guaranteed to be visible to other threads, and
 * non-final fields may still hold default ({@code 0}/{@code null}) values. If the
 * constructor hands {@code this} to another thread — by starting a {@code Thread},
 * registering a listener/callback, publishing into a static or shared collection,
 * or passing {@code this} to an executor — that thread can observe a
 * partially-constructed object. The classic forms:
 *
 * <pre>{@code
 * class Service {
 *     Service(EventBus bus) {
 *         bus.register(this);          // listener may fire before ctor returns
 *         new Thread(this::poll).start(); // thread runs against half-built state
 *     }
 * }
 * }</pre>
 *
 * <p>This complements the library's constructor-safety (final-field publication)
 * checks: it targets the <em>publication</em> act rather than the field declaration.
 *
 * <p>Because the escape is a construction-time event, the detector is cooperative:
 * the constructor under test reports each publication of {@code this} via
 * {@link #recordConstructorEscape}, and optionally marks completion via
 * {@link #recordConstructionComplete}. Any recorded escape is flagged; an escape
 * observed by a thread other than the constructing thread is escalated as a
 * confirmed cross-thread publication.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = ThisEscapeDetector...;
 * // inside the constructor, at the point this leaks:
 * d.recordConstructorEscape(this, "EventBus.register(this)", Thread.currentThread());
 * // at the end of the constructor (optional):
 * d.recordConstructionComplete(this);
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; escape descriptions and observer-thread sets are ConcurrentHashMap.newKeySet(); the completed flag is volatile.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ThisEscapeDetectorTest.java"
)
public final class ThisEscapeDetector {

    private static final class State {
        final String label;
        final long constructingThreadId;
        final Set<String> escapes        = ConcurrentHashMap.newKeySet();
        final Set<Long>   observerThreads = ConcurrentHashMap.newKeySet();
        volatile boolean completed = false;

        State(String label, long constructingThreadId) {
            this.label = label;
            this.constructingThreadId = constructingThreadId;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record that a constructor published {@code this} before returning.
     *
     * @param instance the object being constructed (null-safe)
     * @param how      description of the publication (e.g. {@code "bus.register(this)"})
     * @param thread   the constructing thread
     */
    public void recordConstructorEscape(Object instance, String how, Thread thread) {
        if (instance == null || thread == null) return;
        int id = System.identityHashCode(instance);
        State s = instances.get(id);
        if (s == null) {
            final String label = instance.getClass().getSimpleName() + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label, thread.threadId()));
        }
        s.escapes.add(how != null ? how : "this published from constructor");
    }

    /**
     * Record that another thread accessed the instance. If this happens before
     * {@link #recordConstructionComplete}, it confirms a thread observed a
     * partially-constructed object.
     *
     * @param instance the escaped object (null-safe)
     * @param thread   the observing thread
     */
    public void recordExternalAccess(Object instance, Thread thread) {
        if (instance == null || thread == null) return;
        State s = instances.get(System.identityHashCode(instance));
        if (s == null) return; // no escape recorded for this instance — nothing to correlate
        if (!s.completed && thread.threadId() != s.constructingThreadId) {
            s.observerThreads.add(thread.threadId());
        }
    }

    /**
     * Mark construction of {@code instance} as complete. Accesses after this point
     * are safe and no longer escalate escapes.
     *
     * @param instance the now fully-constructed object (null-safe)
     */
    public void recordConstructionComplete(Object instance) {
        if (instance == null) return;
        State s = instances.get(System.identityHashCode(instance));
        if (s != null) s.completed = true;
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.escapes.isEmpty()) continue;
            boolean observed = !s.observerThreads.isEmpty();
            IssueSeverity severity = observed ? IssueSeverity.HIGH : IssueSeverity.MEDIUM;
            String msg = String.format(
                    "Constructor of '%s' published 'this' before returning via [%s]%s — "
                            + "other threads may observe a partially-constructed object "
                            + "(uninitialized fields, no final-field visibility guarantee).",
                    s.label,
                    String.join("; ", s.escapes),
                    observed
                            ? " and " + s.observerThreads.size()
                              + " other thread(s) accessed it before construction completed"
                            : "");
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "ThisEscape",
                    severity,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "escapeCount", s.escapes.size(),
                            "observedBeforeComplete", observed),
                    Instant.now()));
        }
        return r;
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
            if (violations.isEmpty()) return "THIS-ESCAPE — clean";
            StringBuilder sb = new StringBuilder("THIS-ESCAPE FROM CONSTRUCTOR DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Do not start threads or register listeners inside the constructor.\n")
              .append("    - Use a static factory: construct fully, then publish via a separate start()/init().\n")
              .append("    - If a listener must be registered, do it after the constructor returns.\n");
            return sb.toString();
        }
    }
}
