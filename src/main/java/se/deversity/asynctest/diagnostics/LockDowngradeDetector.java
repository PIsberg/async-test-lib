package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Detects incorrect {@link java.util.concurrent.locks.ReentrantReadWriteLock} downgrade and
 * upgrade patterns.
 *
 * <h2>The upgrade problem (detected)</h2>
 * {@code ReentrantReadWriteLock} does <strong>not</strong> support read-to-write upgrade.
 * A thread holding a read lock that calls {@code writeLock().lock()} will deadlock immediately
 * because the write lock cannot be granted while any read lock is held — including the one held
 * by the same thread.
 *
 * <h2>Correct downgrade pattern (not flagged)</h2>
 * <ol>
 *   <li>Acquire write lock</li>
 *   <li>Acquire read lock (while still holding write lock)</li>
 *   <li>Release write lock</li>
 *   <li>Perform reads, then release read lock</li>
 * </ol>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectLockDowngrade = true)
 * void testLockUpgrade() {
 *     LockDowngradeDetector mon = AsyncTestContext.lockDowngradeMonitor();
 *     rwLock.readLock().lock();
 *     mon.recordReadLockAcquired(rwLock, "myLock");
 *     try {
 *         // BUG: upgrading read → write — will deadlock
 *         mon.recordWriteLockAcquired(rwLock, "myLock");
 *         rwLock.writeLock().lock();
 *     } finally {
 *         mon.recordWriteLockReleased(rwLock, "myLock");
 *         rwLock.writeLock().unlock();
 *         mon.recordReadLockReleased(rwLock, "myLock");
 *         rwLock.readLock().unlock();
 *     }
 * }
 * }</pre>
 */
public class LockDowngradeDetector {

    /**
     * Per-thread hold counters. A single "R or W" marker cannot represent the
     * mid-downgrade state where a thread holds both locks: the read acquire
     * overwrote the write marker (flagging a legal reentrant write acquire as an
     * upgrade), and the write release then erased the read record entirely
     * (missing a genuine read→write upgrade attempted after a downgrade).
     */
    private static final class Holds {
        int read;
        int write;
    }

    private static class LockState {
        final String name;
        final Map<Long, Holds> threadHolds = new ConcurrentHashMap<>();
        final AtomicInteger upgradeAttempts = new AtomicInteger(0);
        final List<String> upgradeDetails  = new CopyOnWriteArrayList<>();

        LockState(String name) { this.name = name; }
    }

    private final Map<Integer, LockState> locks = new ConcurrentHashMap<>();

    private LockState stateFor(ReadWriteLock lock, String name) {
        return locks.computeIfAbsent(System.identityHashCode(lock), k -> {
            String resolved = name != null ? name : "rwlock@" + k;
            return new LockState(resolved);
        });
    }

    /**
     * Record that the current thread acquired the <em>read</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordReadLockAcquired(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        LockState state = stateFor(lock, lockName);
        long tid = Thread.currentThread().threadId();
        // Acquiring read while already holding write is the valid downgrade step —
        // recorded additively so the write hold is not forgotten.
        state.threadHolds.compute(tid, (k, h) -> {
            if (h == null) h = new Holds();
            h.read++;
            return h;
        });
    }

    /**
     * Record that the current thread released the <em>read</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordReadLockReleased(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        stateFor(lock, lockName).threadHolds.computeIfPresent(
            Thread.currentThread().threadId(), (k, h) -> {
                if (h.read > 0) h.read--;
                return (h.read == 0 && h.write == 0) ? null : h;
            });
    }

    /**
     * Record that the current thread is about to acquire the <em>write</em> lock.
     * If the thread already holds the <em>read</em> lock on the same {@link ReadWriteLock},
     * an upgrade attempt is recorded.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordWriteLockAcquired(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        LockState state = stateFor(lock, lockName);
        long tid = Thread.currentThread().threadId();
        state.threadHolds.compute(tid, (k, h) -> {
            if (h == null) h = new Holds();
            // Upgrade = acquiring write while holding read but NOT write. Holding
            // write too (mid-downgrade) makes this a legal reentrant acquire.
            if (h.read > 0 && h.write == 0) {
                state.upgradeAttempts.incrementAndGet();
                state.upgradeDetails.add(String.format(
                    "Thread '%s' attempted read→write upgrade on lock '%s' — "
                    + "ReentrantReadWriteLock does not support upgrade; this will deadlock",
                    Thread.currentThread().getName(), state.name));
            }
            h.write++;
            return h;
        });
    }

    /**
     * Record that the current thread released the <em>write</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordWriteLockReleased(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        stateFor(lock, lockName).threadHolds.computeIfPresent(
            Thread.currentThread().threadId(), (k, h) -> {
                if (h.write > 0) h.write--;
                return (h.read == 0 && h.write == 0) ? null : h;
            });
    }

    /**
     * Analyze for invalid lock upgrade/downgrade patterns.
     *
     * @return report describing detected issues
     */
    public LockDowngradeReport analyze() {
        LockDowngradeReport report = new LockDowngradeReport();
        for (LockState state : locks.values()) {
            report.upgradeAttempts.addAll(state.upgradeDetails);
        }
        return report;
    }

    /** Report produced by {@link #analyze()}. */
    public static class LockDowngradeReport {
        final List<String> upgradeAttempts = new ArrayList<>();

        public boolean hasIssues() {
            return !upgradeAttempts.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LOCK UPGRADE/DOWNGRADE ISSUES DETECTED:\n");
            for (String issue : upgradeAttempts) sb.append("  - ").append(issue).append("\n");
            sb.append("  Why: ReentrantReadWriteLock does not support upgrade (read→write). A thread holding a read lock that\n" +
"       tries to acquire the write lock will deadlock against itself, since the write lock requires all\n" +
"       read locks to be released first — including its own.\n" +
"  Fix:\n" +
"    - To upgrade: release the read lock, then acquire the write lock (re-validate the condition after)\n" +
"    - For safe downgrade: acquire the read lock while still holding the write lock, THEN release the write lock\n" +
"    - Consider StampedLock.tryConvertToWriteLock(stamp) if upgrade is a frequent pattern");
            return sb.toString();
        }
    }
}
