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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Detects attempts to upgrade a ReentrantReadWriteLock from a read lock to a write lock
 * on the same thread, which inevitably deadlocks.
 *
 * <p>This is the detector named for that condition, and the one that reports it. {@link
 * LockDowngradeDetector} observes the same upgrade through its own recording API, and when both
 * are enabled it forwards what it records here and leaves the finding to this class, so one
 * upgrade produces one finding whichever API a caller instrumented. See {@link
 * LockDowngradeDetector#deferUpgradeReportingTo} and issue #361.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap tracks read lock ownership and violations.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/LockUpgradeDeadlockDetectorTest.java"
)
public final class LockUpgradeDeadlockDetector {

    private static final class State {
        final String lockName;
        final Set<String> deadlockedThreads = ConcurrentHashMap.newKeySet();

        State(String lockName) {
            this.lockName = lockName;
        }
    }

    /**
     * Read holds per lock, per thread, as a count. A set lost the second of two nested read
     * acquires at the first release, so a thread still holding the read lock read as free and
     * its write attempt, which really blocks forever, went unreported (#566).
     *
     * <p>Locks are keyed by identity. Keyed by the bare identity hash, two locks that shared one
     * were one lock, and a read hold on one made a write attempt on the other an upgrade.
     */
    private final Map<IdentityKey, Map<Long, Integer>> readHolds = new ConcurrentHashMap<>();
    private final Map<IdentityKey, State> violations = new ConcurrentHashMap<>();

    /**
     * Record acquisition of a read lock.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param thread the thread performing the operation
     */
    public void recordReadLockAcquired(ReentrantReadWriteLock lock, String lockName, Thread thread) {
        if (lock == null || thread == null) return;
        readHolds.computeIfAbsent(new IdentityKey(lock), k -> new ConcurrentHashMap<>())
                .merge(thread.threadId(), 1, Integer::sum);
    }

    /**
     * Record release of a read lock.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param thread the thread performing the operation
     */
    public void recordReadLockReleased(ReentrantReadWriteLock lock, Thread thread) {
        if (lock == null || thread == null) return;
        Map<Long, Integer> holds = readHolds.get(new IdentityKey(lock));
        if (holds != null) {
            holds.computeIfPresent(thread.threadId(), (k, count) -> count > 1 ? count - 1 : null);
        }
    }

    /**
     * Record attempt to acquire a write lock.
     *
     * <p>Reported only where {@link ReentrantReadWriteLock} really deadlocks: the thread holds the
     * read lock and does not hold the write lock. A thread that holds the write lock may take the
     * read lock and then the write lock again, which is the reentrant acquire the JDK's own
     * downgrading example relies on. When the recording thread is the calling thread and really
     * holds the lock, the lock is asked directly: {@link
     * ReentrantReadWriteLock#isWriteLockedByCurrentThread()} and {@link
     * ReentrantReadWriteLock#getReadHoldCount()} are exact current-thread queries that take
     * nothing. Otherwise the decision rests on the recorded read holds, so a body that declares
     * acquisitions without taking the lock is still judged by what it declared.
     *
     * <p>Record only a blocking {@code writeLock().lock()}. A {@code tryLock()} made while holding
     * the read lock returns {@code false} at once and does not deadlock, so recording it here
     * would report a deadlock that cannot happen.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param thread the thread performing the operation
     */
    public void recordWriteLockAcquisitionAttempt(ReentrantReadWriteLock lock, String lockName, Thread thread) {
        if (lock == null || thread == null) return;
        IdentityKey id = new IdentityKey(lock);
        boolean upgrade;
        if (thread.threadId() == Thread.currentThread().threadId()
                && (lock.isWriteLockedByCurrentThread() || lock.getReadHoldCount() > 0)) {
            // The calling thread really holds this lock, so the lock answers exactly. Records are
            // only consulted for a body that declares acquisitions without taking the lock.
            upgrade = !lock.isWriteLockedByCurrentThread();
        } else {
            Map<Long, Integer> holds = readHolds.get(id);
            upgrade = holds != null && holds.containsKey(thread.threadId());
        }
        if (upgrade) {
            State s = violations.computeIfAbsent(id, k -> new State(
                lockName != null ? lockName : "ReentrantReadWriteLock@" + id.hashCode()
            ));
            s.deadlockedThreads.add(thread.getName());
        }
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
                "Read-Write Lock '%s' upgrade attempt detected by threads %s. A thread holding a read lock cannot acquire a write lock on the same ReentrantReadWriteLock instance, resulting in a permanent deadlock.",
                s.lockName, String.join(", ", s.deadlockedThreads)
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "LockUpgradeDeadlock",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "lockName", s.lockName,
                    "deadlockedThreads", List.copyOf(s.deadlockedThreads)
                ),
                Instant.now()
            ));
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
            if (violations.isEmpty()) return "LOCK UPGRADE DEADLOCK — clean";
            StringBuilder sb = new StringBuilder("LOCK UPGRADE DEADLOCK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Release the read lock prior to requesting the write lock:\n")
              .append("      lock.readLock().unlock(); lock.writeLock().lock();\n")
              .append("    - Ensure read locks are never held while acquiring write locks on the same lock object.\n");
            return sb.toString();
        }
    }
}
