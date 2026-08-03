package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects attempted {@link Object#notify()} / {@link Object#notifyAll()} calls
 * where the calling thread does not hold the target monitor.
 *
 * <p><strong>Why it matters.</strong> Java mandates that {@code notify()} and
 * {@code notifyAll()} only be called while holding the lock on the object
 * being notified. The JVM enforces this at runtime by throwing
 * {@link IllegalMonitorStateException}. In production, that exception is
 * usually caught at a high level (e.g. a worker's catch-all) and silently
 * swallowed, leaving the actual {@code wait()}-ers blocked forever and the
 * symptom looking like a deadlock rather than a missed signal.
 *
 * <p>This detector lets test code <em>declare</em> a notify attempt without
 * actually invoking the JDK call:
 *
 * <pre>{@code
 * synchronized (mutex) {
 *     d.recordNotifyAttempt(mutex, "queue-not-empty");
 *     mutex.notifyAll();   // would throw IllegalMonitorStateException if not synchronized
 * }
 * }</pre>
 *
 * <p>The detector samples {@link Thread#holdsLock(Object)} at the moment the
 * attempt is recorded. If the calling thread does not hold the monitor, the
 * call is flagged.
 *
 * <p>Complements {@link MissedSignalDetector} (which catches notifies with no
 * waiter) by flagging the inverse: notifies that are illegal regardless of
 * whether anyone is waiting.
 *
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Attempts list mutated under a single intrinsic monitor on the list itself; sampling Thread.holdsLock requires no locking.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/NotifyWithoutMonitorDetectorTest.java"
)
public final class NotifyWithoutMonitorDetector {

    private static final class Attempt {
        final String monitorLabel;
        final String threadName;
        final long   threadId;

        Attempt(String monitorLabel, String threadName, long threadId) {
            this.monitorLabel = monitorLabel;
            this.threadName = threadName;
            this.threadId = threadId;
        }
    }

    private final List<Attempt> attempts = new ArrayList<>();
    // Per-monitor monotonic counter is not needed; the violations list captures
    // every illegal call in order so the user sees the full timeline.

    /**
     * Record a {@code notify()} / {@code notifyAll()} attempt on {@code monitor}.
     *
     * <p>Call this <em>before</em> the actual {@code notify*()} call. The
     * detector samples {@link Thread#holdsLock(Object)} synchronously; if the
     * calling thread does not hold the monitor, the attempt is queued for
     * reporting.
     *
     * @param monitor the object the notify is being called on (null-safe)
     * @param label   descriptive name for the report (may be {@code null})
     */
    public void recordNotifyAttempt(Object monitor, String label) {
        if (monitor == null) return;
        boolean held = Thread.holdsLock(monitor);
        if (held) return; // legal call; nothing to report
        String effectiveLabel = (label != null)
                ? label
                : monitor.getClass().getSimpleName() + "@" + System.identityHashCode(monitor);
        synchronized (attempts) {
            attempts.add(new Attempt(effectiveLabel,
                    Thread.currentThread().getName(),
                    Thread.currentThread().threadId()));
        }
    }

    /** Report produced by {@link #analyze()}. */
    public Report analyze() {
        Report r = new Report();
        synchronized (attempts) {
            for (Attempt a : attempts) {
                String msg = String.format(
                        "notify()/notifyAll() on '%s' attempted by thread '%s' (id=%d) "
                                + "without holding the monitor — IllegalMonitorStateException "
                                + "would be thrown at runtime; any wait()-ers on this monitor "
                                + "will not be released.",
                        a.monitorLabel, a.threadName, a.threadId);
                r.violations.add(msg);
                r.structuredViolations.add(new Violation(
                        "NotifyWithoutMonitor",
                        IssueSeverity.HIGH,
                        msg,
                        List.of(),
                        Map.of(
                                "monitor", a.monitorLabel,
                                "threadName", a.threadName,
                                "threadId", a.threadId),
                        Instant.now()));
            }
        }
        return r;
    }

    /** Report. */
    public static final class Report {
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "NOTIFY WITHOUT MONITOR — clean";
            StringBuilder sb = new StringBuilder("ILLEGAL NOTIFY ATTEMPTS DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Wrap the notify call in synchronized(monitor) { monitor.notifyAll(); }\n")
              .append("    - Or use a java.util.concurrent.locks.Condition with its companion Lock —\n")
              .append("      the Lock.lock() requirement is enforced by the API rather than at runtime.\n");
            return sb.toString();
        }
    }
}
