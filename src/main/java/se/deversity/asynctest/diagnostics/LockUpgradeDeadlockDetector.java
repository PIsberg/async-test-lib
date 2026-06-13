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

    private final Map<Integer, Set<Long>> readHolders = new ConcurrentHashMap<>();
    private final Map<Integer, State> violations = new ConcurrentHashMap<>();

    /**
     * Record acquisition of a read lock.
     */
    public void recordReadLockAcquired(ReentrantReadWriteLock lock, String lockName, Thread thread) {
        if (lock == null || thread == null) return;
        int id = System.identityHashCode(lock);
        readHolders.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(thread.getId());
    }

    /**
     * Record release of a read lock.
     */
    public void recordReadLockReleased(ReentrantReadWriteLock lock, Thread thread) {
        if (lock == null || thread == null) return;
        int id = System.identityHashCode(lock);
        Set<Long> holders = readHolders.get(id);
        if (holders != null) {
            holders.remove(thread.getId());
        }
    }

    /**
     * Record attempt to acquire a write lock.
     */
    public void recordWriteLockAcquisitionAttempt(ReentrantReadWriteLock lock, String lockName, Thread thread) {
        if (lock == null || thread == null) return;
        int id = System.identityHashCode(lock);
        Set<Long> holders = readHolders.get(id);
        if (holders != null && holders.contains(thread.getId())) {
            State s = violations.computeIfAbsent(id, k -> new State(
                lockName != null ? lockName : "ReentrantReadWriteLock@" + id
            ));
            s.deadlockedThreads.add(thread.getName());
        }
    }

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
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

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
