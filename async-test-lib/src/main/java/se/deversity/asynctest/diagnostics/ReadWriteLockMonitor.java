package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors ReadWriteLock fairness and detects writer starvation.
 * 
 * Problems detected:
 * - Writers starved by constant readers
 * - Readers blocked by writer preference
 * - Unfair lock distribution
 */
public class ReadWriteLockMonitor {
    
    private static class LockState {
        final String lockName;
        final AtomicLong readLockCount = new AtomicLong(0);
        final AtomicLong writeLockCount = new AtomicLong(0);
        final AtomicLong readWaitTime = new AtomicLong(0);
        final AtomicLong writeWaitTime = new AtomicLong(0);
        volatile long maxWriteWaitTime = 0;
        final Set<Long> currentReaders = ConcurrentHashMap.newKeySet();
        volatile long currentWriter = -1;
        final AtomicInteger writerStarvations = new AtomicInteger(0);
        
        LockState(String name) {
            this.lockName = name;
        }
    }
    
    private final Map<Integer, LockState> locks = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a read-write lock for monitoring.
     *
     * @param rwLock the read-write lock being recorded, tracked by identity
     * @param name a label identifying the rw lock in the report
     */
    public void registerLock(Object rwLock, String name) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        locks.putIfAbsent(id, new LockState(name));
    }
    
    /**
     * Record read lock acquisition.
     *
     * @param rwLock the read-write lock being recorded, tracked by identity
     * @param waitTimeMs the wait time in milliseconds
     */
    public void recordReadLockAcquired(Object rwLock, long waitTimeMs) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.readLockCount.incrementAndGet();
        state.readWaitTime.addAndGet(waitTimeMs);
        state.currentReaders.add(Thread.currentThread().threadId());
    }
    
    /**
     * Record read lock release.
     *
     * @param rwLock the read-write lock being recorded, tracked by identity
     */
    public void recordReadLockReleased(Object rwLock) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.currentReaders.remove(Thread.currentThread().threadId());
    }
    
    /**
     * Record write lock acquisition.
     *
     * @param rwLock the read-write lock being recorded, tracked by identity
     * @param waitTimeMs the wait time in milliseconds
     */
    public void recordWriteLockAcquired(Object rwLock, long waitTimeMs) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.writeLockCount.incrementAndGet();
        state.writeWaitTime.addAndGet(waitTimeMs);
        synchronized (state) {
            state.maxWriteWaitTime = Math.max(state.maxWriteWaitTime, waitTimeMs);
        }
        state.currentWriter = Thread.currentThread().threadId();
        
        // Check for writer starvation (lots of readers, high write wait time)
        if (waitTimeMs > 100 && state.readLockCount.get() > state.writeLockCount.get() * 2) {
            state.writerStarvations.incrementAndGet();
        }
    }
    
    /**
     * Record write lock release.
     *
     * @param rwLock the read-write lock being recorded, tracked by identity
     */
    public void recordWriteLockReleased(Object rwLock) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.currentWriter = -1;
    }
    
    /**
     * Analyze read-write lock fairness.
     *
     * @return the findings this detector collected during the run
     */
    public ReadWriteLockReport analyzeFairness() {
        ReadWriteLockReport report = new ReadWriteLockReport();
        
        for (LockState state : locks.values()) {
            long reads = state.readLockCount.get();
            long writes = state.writeLockCount.get();
            
            if (reads == 0 && writes == 0) continue;
            
            // Check for reader/writer imbalance
            double ratio = reads / (double) Math.max(1, writes);
            if (ratio > 10) {
                report.readerDominatedLocks.add(String.format(
                    "%s: %.1fx more reads than writes (may cause writer starvation)",
                    state.lockName, ratio
                ));
            }
            
            // Check for writer starvation
            int starv = state.writerStarvations.get();
            if (starv > 0) {
                report.starvedWriters.add(String.format(
                    "%s: Writers starved %d times (max wait: %dms)",
                    state.lockName, starv, state.maxWriteWaitTime
                ));
            }
            
            // Check for long write waits
            if (state.maxWriteWaitTime > 50) {
                report.longWriteWaits.add(String.format(
                    "%s: Max write wait time %dms",
                    state.lockName, state.maxWriteWaitTime
                ));
            }
            
            // Check if currently held
            if (state.currentWriter >= 0) {
                report.currentWriteHolders.add(state.lockName);
            }
            if (!state.currentReaders.isEmpty()) {
                report.currentReadHolders.add(state.lockName + " (" + state.currentReaders.size() + " readers)");
            }
        }
        
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeFairness()}.
     *
     * @return the findings this detector collected during the run
     */
    public ReadWriteLockReport analyze() {
        return analyzeFairness();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        locks.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }
    
    public static class ReadWriteLockReport {
        /** Locks where readers arrived often enough to hold writers off. */
        public final Set<String> readerDominatedLocks = new HashSet<>();
        /** Writers that never acquired the lock during the run. */
        public final Set<String> starvedWriters = new HashSet<>();
        /** Writers that waited longer than the reporting threshold. */
        public final Set<String> longWriteWaits = new HashSet<>();
        /** Threads currently holding the write lock. */
        public final Set<String> currentWriteHolders = new HashSet<>();
        /** Threads currently holding the read lock. */
        public final Set<String> currentReadHolders = new HashSet<>();
        
        /**
         * {@return whether this report should surface as a finding}
         *
         * <p>The canonical predicate {@code LegacyDetectorAdapter} binds to. Without it the
         * adapter resolved the report method, found no {@code hasIssues()} on the returned type,
         * and emitted an empty violation list on every call — leaving this detector registered,
         * addressable and structurally silent. Pinned by {@code DetectorFiringContractTest}.
         *
         * @since 1.9.2
         */
        public boolean hasIssues() {
            return hasFairnessIssues();
        }

        /**
         * {@return whether there are fairness issues}
         */
        public boolean hasFairnessIssues() {
            return !readerDominatedLocks.isEmpty() || !starvedWriters.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasFairnessIssues()) {
                return "No read-write lock fairness issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("READ-WRITE LOCK FAIRNESS ISSUES:\n");
            
            if (!readerDominatedLocks.isEmpty()) {
                sb.append("\nReader-dominated locks (may starve writers):\n");
                for (String issue : readerDominatedLocks) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Use writer preference or fair RWLock\n");
            }
            
            if (!starvedWriters.isEmpty()) {
                sb.append("\nStarved writers:\n");
                for (String issue : starvedWriters) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!longWriteWaits.isEmpty()) {
                sb.append("\nLong write wait times:\n");
                for (String issue : longWriteWaits) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
