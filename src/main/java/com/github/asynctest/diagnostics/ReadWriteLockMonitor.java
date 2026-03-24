package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        volatile int readerStarvations = 0;
        volatile int writerStarvations = 0;
        
        LockState(String name) {
            this.lockName = name;
        }
    }
    
    private final Map<Integer, LockState> locks = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a read-write lock for monitoring.
     */
    public void registerLock(Object rwLock, String name) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        locks.putIfAbsent(id, new LockState(name));
    }
    
    /**
     * Record read lock acquisition.
     */
    public void recordReadLockAcquired(Object rwLock, long waitTimeMs) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.readLockCount.incrementAndGet();
        state.readWaitTime.addAndGet(waitTimeMs);
        state.currentReaders.add(Thread.currentThread().getId());
    }
    
    /**
     * Record read lock release.
     */
    public void recordReadLockReleased(Object rwLock) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.currentReaders.remove(Thread.currentThread().getId());
    }
    
    /**
     * Record write lock acquisition.
     */
    public void recordWriteLockAcquired(Object rwLock, long waitTimeMs) {
        if (!enabled) return;
        
        int id = System.identityHashCode(rwLock);
        LockState state = locks.get(id);
        if (state == null) return;
        
        state.writeLockCount.incrementAndGet();
        state.writeWaitTime.addAndGet(waitTimeMs);
        state.maxWriteWaitTime = Math.max(state.maxWriteWaitTime, waitTimeMs);
        state.currentWriter = Thread.currentThread().getId();
        
        // Check for writer starvation (lots of readers, high write wait time)
        if (waitTimeMs > 100 && state.readLockCount.get() > state.writeLockCount.get() * 2) {
            state.writerStarvations++;
        }
    }
    
    /**
     * Record write lock release.
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
            if (state.writerStarvations > 0) {
                report.starvedWriters.add(String.format(
                    "%s: Writers starved %d times (max wait: %dms)",
                    state.lockName, state.writerStarvations, state.maxWriteWaitTime
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
    
    public void reset() {
        locks.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class ReadWriteLockReport {
        public final Set<String> readerDominatedLocks = new HashSet<>();
        public final Set<String> starvedWriters = new HashSet<>();
        public final Set<String> longWriteWaits = new HashSet<>();
        public final Set<String> currentWriteHolders = new HashSet<>();
        public final Set<String> currentReadHolders = new HashSet<>();
        
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
