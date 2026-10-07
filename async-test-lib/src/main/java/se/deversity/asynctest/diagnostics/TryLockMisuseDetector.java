package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Detects misuse of Lock.tryLock(), such as calling unlock() unconditionally
 * when tryLock() returned false.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap tracks tryLock attempts, results, and unlock violations.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/TryLockMisuseDetectorTest.java"
)
public final class TryLockMisuseDetector extends AbstractInstanceDetector<TryLockMisuseDetector.State> {

    static final class State {
        final String label;
        /** Per thread, what its last tryLock() returned, until it acquires or unlocks. */
        final Map<Long, Boolean> results = new ConcurrentHashMap<>();
        final Set<String> threadsWithViolations = ConcurrentHashMap.newKeySet();
        /** The name the first named misused unlock() gave, which the report prints over the label. */
        final AtomicReference<@Nullable String> lockName = new AtomicReference<>();

        State(String label) {
            this.label = label;
        }

        String reportedName() {
            String name = lockName.get();
            return name != null ? name : label;
        }
    }

    @Override
    State newState(Object instance, String label) {
        return new State(label);
    }

    /**
     * Record the result of a tryLock() call.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param acquired the {@code acquired} flag
     * @param thread the thread performing the operation
     */
    public void recordTryLockResult(Object lock, String lockName, boolean acquired, Thread thread) {
        if (lock == null || thread == null) return;
        // Registered unnamed: a finding is named by the misused unlock(), as it always was.
        stateFor(lock, null, "Lock").results.put(thread.threadId(), acquired);
    }

    /**
     * Record that {@code thread} now holds {@code lock} by some route other than {@code tryLock},
     * such as a blocking {@code lock()}.
     *
     * <p>This forgets the thread's last {@code tryLock} outcome on that lock, so the common
     * fallback {@code if (!lock.tryLock()) lock.lock();} is not judged at its {@code unlock()} by
     * the failed try it already recovered from (#757). A failed try followed directly by an
     * unlock still reports.
     *
     * @param lock the lock now held, tracked by identity rather than equality
     * @param thread the thread that acquired it
     */
    public void recordLockAcquired(Object lock, Thread thread) {
        if (lock == null || thread == null || states().isEmpty()) return;
        State s = trackedState(lock);
        if (s != null) {
            s.results.remove(thread.threadId());
        }
    }

    /**
     * Record an unlock() call.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param thread the thread performing the operation
     */
    public void recordUnlock(Object lock, String lockName, Thread thread) {
        if (lock == null || thread == null) return;
        State s = trackedState(lock);
        if (s != null) {
            Boolean acquired = s.results.get(thread.threadId());
            if (acquired != null && !acquired) {
                if (lockName != null) s.lockName.compareAndSet(null, lockName);
                s.threadsWithViolations.add(thread.getName());
            }
            s.results.remove(thread.threadId());
        }
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : states()) {
            if (s.threadsWithViolations.isEmpty()) continue;
            String msg = String.format(
                "Lock '%s' tryLock misuse detected by threads %s. Threads called unlock() after tryLock() returned false (or without verifying acquisition), which throws IllegalMonitorStateException or corrupts lock state.",
                s.reportedName(), String.join(", ", s.threadsWithViolations)
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "TryLockMisuse",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "lockName", s.reportedName(),
                    "threadsWithViolations", List.copyOf(s.threadsWithViolations)
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
            if (violations.isEmpty()) return "TRY LOCK MISUSE — clean";
            StringBuilder sb = new StringBuilder("TRY LOCK MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Only call unlock() if tryLock() returned true:\n")
              .append("      if (lock.tryLock()) { try { ... } finally { lock.unlock(); } }\n");
            return sb.toString();
        }
    }
}
