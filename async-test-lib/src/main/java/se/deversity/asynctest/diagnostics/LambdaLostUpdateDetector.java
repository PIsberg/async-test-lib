package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

/**
 * Proves lost updates to a lambda's captured state by comparing the values two threads observed,
 * rather than inferring a race from the fact that a lambda ran on more than one thread.
 *
 * <p>{@link StatefulLambdaDetector} reports the <em>shape</em> of the hazard: a lambda that ran on
 * several threads and mutated something it captured. That is a co-occurrence, so it fires the same
 * way on a captured counter guarded by a lock as on a racy one. This detector answers the narrower
 * question with evidence: did two threads read the <em>same</em> pre-value and both write back? If
 * they did, one of those writes was computed from state that was already stale when it was
 * written, and it is gone. That is a lost update, not a risk of one.
 *
 * <p>Consequently:
 * <ul>
 *   <li>{@code counter[0] = counter[0] + 1} from N threads fires - two threads read the same
 *       value.</li>
 *   <li>{@code counter.incrementAndGet()} on an {@link java.util.concurrent.atomic.AtomicInteger}
 *       stays silent - every thread observes a distinct pre-value.</li>
 *   <li>The same read-modify-write under a consistently held monitor stays silent - see
 *       {@link #recordReadModifyWrite(Object, String, Object, Object, Object, Thread)}.</li>
 * </ul>
 *
 * <p>The lock probe uses {@link Thread#holdsLock(Object)} at record time, so a guard that is taken
 * around only the read or only the write does not suppress the finding. When guarding is
 * inconsistent - some events hold the monitor, some do not - the finding stands and says so, which
 * is the case where a lock gives the most misleading sense of safety.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * int[] counter = {0};
 * Runnable task = () -> {
 *     var d = AsyncTestContext.lambdaLostUpdateDetector();
 *     int before = counter[0];              // the read
 *     int after  = before + 1;              // the modify
 *     counter[0] = after;                   // the write
 *     d.recordReadModifyWrite(task, "counter", before, after, Thread.currentThread());
 * };
 * }</pre>
 *
 * @since 1.10.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "ConcurrentHashMap keyed on lambda identity plus captured name; events are appended to a "
             + "copy-on-write list. The rule groups by observed pre-value and needs no ordering. "
             + "holdsLock is sampled on the recording thread at record time, which is the only place "
             + "it means anything.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/LambdaLostUpdateDetectorTest.java"
)
public final class LambdaLostUpdateDetector {

    private static final class Rmw {
        final long    threadId;
        final String  threadName;
        final String  before;
        final String  written;
        final int     guardId;      // 0 when no guard was named
        final boolean heldGuard;

        Rmw(long threadId, String threadName, String before, String written,
            int guardId, boolean heldGuard) {
            this.threadId   = threadId;
            this.threadName = threadName;
            this.before     = before;
            this.written    = written;
            this.guardId    = guardId;
            this.heldGuard  = heldGuard;
        }
    }

    private static final class CaptureState {
        final String     lambdaName;
        final String     capturedName;
        final List<Rmw>  events = new CopyOnWriteArrayList<>();

        CaptureState(String lambdaName, String capturedName) {
            this.lambdaName   = lambdaName;
            this.capturedName = capturedName;
        }
    }

    private final Map<String, CaptureState> captures = new ConcurrentHashMap<>();
    private volatile boolean                enabled  = true;

    /**
     * Record one read-modify-write of a captured variable, with no guard object.
     *
     * @param lambda         the lambda, {@link Runnable} or {@link java.util.concurrent.Callable}
     *                       instance doing the update, tracked by identity
     * @param capturedName   name of the captured variable, e.g. {@code "counter"}
     * @param observedBefore the value read at the start of the update
     * @param written        the value written back at the end of it
     * @param thread         the updating thread
     */
    public void recordReadModifyWrite(Object lambda, String capturedName,
                                      Object observedBefore, Object written, Thread thread) {
        recordReadModifyWrite(lambda, capturedName, observedBefore, written, null, thread);
    }

    /**
     * Record one read-modify-write of a captured variable, naming the monitor that is supposed to
     * make it atomic.
     *
     * <p>The detector samples {@link Thread#holdsLock(Object)} on {@code guard} at the moment of
     * the call. When every recorded update to this capture held the same monitor, the update
     * sequence is serialised and no finding is produced, even if two threads saw the same
     * pre-value - under a consistent lock that means the value legitimately recurred. Missing that
     * case is the safe direction; reporting a correctly locked counter is not.
     *
     * @param lambda         the lambda instance doing the update, tracked by identity
     * @param capturedName   name of the captured variable
     * @param observedBefore the value read at the start of the update
     * @param written        the value written back at the end of it
     * @param guard          the monitor the update is supposed to hold, or {@code null} for none
     * @param thread         the updating thread
     */
    public void recordReadModifyWrite(Object lambda, String capturedName, Object observedBefore,
                                      Object written, @Nullable Object guard, Thread thread) {
        if (!enabled || lambda == null || thread == null) return;
        String key = System.identityHashCode(lambda) + "#"
                   + (capturedName != null ? capturedName : "capturedState");
        CaptureState s = captures.computeIfAbsent(key, k -> new CaptureState(
                lambda.getClass().getSimpleName() + "@" + System.identityHashCode(lambda),
                capturedName != null ? capturedName : "capturedState"));
        s.events.add(new Rmw(
                thread.threadId(),
                thread.getName(),
                render(observedBefore),
                render(written),
                guard == null ? 0 : System.identityHashCode(guard),
                guard != null && Thread.holdsLock(guard)));
    }

    private static String render(Object value) {
        if (value == null) return "null";
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            // A user toString() that throws must not take the detector down with it.
            return value.getClass().getSimpleName() + "@" + System.identityHashCode(value);
        }
    }

    /** Turn recording off; already-recorded events are kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded updates and reports the ones proven to have lost a write.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (CaptureState s : captures.values()) {
            List<Rmw> events = new ArrayList<>(s.events);
            if (events.size() < 2) continue;

            if (consistentlyGuarded(events)) continue;

            // Group by the value each thread observed before its update. Two different threads
            // in the same group both computed from that value, so the later write overwrote the
            // earlier one without ever having seen it.
            Map<String, List<Rmw>> byBefore = new LinkedHashMap<>();
            for (Rmw e : events) byBefore.computeIfAbsent(e.before, k -> new ArrayList<>()).add(e);

            List<String> collisions = new ArrayList<>();
            Set<String> threads = new LinkedHashSet<>();
            int lostWrites = 0;
            for (Map.Entry<String, List<Rmw>> entry : byBefore.entrySet()) {
                List<Rmw> group = entry.getValue();
                Set<Long> distinctThreads = new LinkedHashSet<>();
                for (Rmw e : group) distinctThreads.add(e.threadId);
                if (distinctThreads.size() < 2) continue;

                StringBuilder wrote = new StringBuilder();
                for (Rmw e : group) {
                    if (wrote.length() > 0) wrote.append(", ");
                    wrote.append('\'').append(e.threadName).append("' wrote ").append(e.written);
                    threads.add(e.threadName);
                }
                lostWrites += group.size() - 1;
                collisions.add("all read " + entry.getKey() + " then " + wrote);
            }
            if (collisions.isEmpty()) continue;

            boolean partiallyGuarded = false;
            for (Rmw e : events) if (e.heldGuard) { partiallyGuarded = true; break; }

            String msg = String.format(
                    "Captured '%s' in lambda %s lost %d update(s): %s. Threads that read the same value before "
                    + "updating it computed from state that was already stale, so every write but the last in "
                    + "each group was discarded.%s",
                    s.capturedName, s.lambdaName, lostWrites, String.join("; ", collisions),
                    partiallyGuarded
                            ? " Some - not all - of these updates held the named monitor, which is worse than "
                              + "none: the lock suggests the sequence is atomic when it is not."
                            : "");

            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "LambdaLostUpdate",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "capturedName", s.capturedName,
                            "lambda", s.lambdaName,
                            "lostWrites", lostWrites,
                            "threads", String.join(",", threads),
                            "partiallyGuarded", partiallyGuarded
                    ),
                    Instant.now()));
        }
        return r;
    }

    /**
     * {@return whether every recorded update held one and the same monitor}
     * Under a consistently held monitor the read-modify-write sequence is serialised, so a
     * repeated pre-value is a legitimately recurring value rather than a lost write.
     */
    private static boolean consistentlyGuarded(List<Rmw> events) {
        int guardId = events.get(0).guardId;
        if (guardId == 0) return false;
        for (Rmw e : events) {
            if (!e.heldGuard || e.guardId != guardId) return false;
        }
        return true;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "LAMBDA CAPTURED-STATE LOST UPDATE - clean";
            StringBuilder sb = new StringBuilder("LAMBDA CAPTURED-STATE LOST UPDATE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: a lambda captures the container, not a copy. When two threads read the captured\n")
              .append("       value, compute from it and write back, the second write is based on a value that was\n")
              .append("       already replaced. Both threads succeed, and one increment simply never happened.\n")
              .append("       This is reported only where two threads were observed reading the same pre-value.\n")
              .append("  Fix:\n")
              .append("    - Replace read-modify-write with a single atomic operation: incrementAndGet(),\n")
              .append("      updateAndGet(), accumulate into a LongAdder\n")
              .append("    - If the update spans several fields, hold one monitor across the whole read, compute\n")
              .append("      and write - not around the read and the write separately\n")
              .append("    - Give each task its own accumulator and combine at the end, so nothing is shared\n");
            return sb.toString();
        }
    }
}
