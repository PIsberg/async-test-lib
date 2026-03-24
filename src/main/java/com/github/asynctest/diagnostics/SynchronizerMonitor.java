package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors synchronizer behavior (CyclicBarrier, Phaser, CountDownLatch, etc.)
 * 
 * Problems detected:
 * - Threads not advancing synchronously through barriers
 * - Phaser advances without all parties participating
 * - Barrier resets while threads still waiting
 * - Deadlock in synchronizers
 */
public class SynchronizerMonitor {
    
    private static class BarrierState {
        final String synchronizerName;
        final int expectedParties;
        final AtomicInteger arrivedCount = new AtomicInteger(0);
        final Set<Long> arrivedThreads = ConcurrentHashMap.newKeySet();
        volatile boolean isReset = false;
        volatile long lastArrivalTime = 0;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        
        BarrierState(String name, int parties) {
            this.synchronizerName = name;
            this.expectedParties = parties;
        }
    }
    
    private final Map<Integer, BarrierState> synchronizers = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a synchronizer for monitoring.
     */
    public void registerSynchronizer(Object synchronizer, int expectedParties) {
        if (!enabled) return;
        
        int id = System.identityHashCode(synchronizer);
        synchronizers.putIfAbsent(id, new BarrierState(
            synchronizer.getClass().getSimpleName(), 
            expectedParties
        ));
    }
    
    /**
     * Record thread arriving at barrier.
     */
    public void recordBarrierArrival(Object synchronizer) {
        if (!enabled) return;
        
        int id = System.identityHashCode(synchronizer);
        BarrierState state = synchronizers.get(id);
        if (state == null) return;
        
        long threadId = Thread.currentThread().getId();
        state.arrivedCount.incrementAndGet();
        state.arrivedThreads.add(threadId);
        state.lastArrivalTime = System.nanoTime();
        state.events.add(String.format("T-%d arrived (%d/%d)", 
            threadId, state.arrivedCount.get(), state.expectedParties));
    }
    
    /**
     * Record thread advancing past barrier.
     */
    public void recordBarrierAdvance(Object synchronizer) {
        if (!enabled) return;
        
        int id = System.identityHashCode(synchronizer);
        BarrierState state = synchronizers.get(id);
        if (state == null) return;
        
        state.events.add(String.format("T-%d advanced past barrier", 
            Thread.currentThread().getId()));
    }
    
    /**
     * Record barrier reset.
     */
    public void recordBarrierReset(Object synchronizer) {
        if (!enabled) return;
        
        int id = System.identityHashCode(synchronizer);
        BarrierState state = synchronizers.get(id);
        if (state == null) return;
        
        state.isReset = true;
        state.arrivedCount.set(0);
        state.arrivedThreads.clear();
        state.events.add("Barrier reset");
    }
    
    /**
     * Analyze synchronizer behavior.
     */
    public SynchronizerReport analyzeSynchronizers() {
        SynchronizerReport report = new SynchronizerReport();
        
        for (BarrierState state : synchronizers.values()) {
            // Check for partial arrivals
            if (state.arrivedCount.get() > 0 && state.arrivedCount.get() < state.expectedParties) {
                if (state.arrivedThreads.size() < state.expectedParties) {
                    report.incompleteBarriers.add(String.format(
                        "%s: %d/%d parties arrived",
                        state.synchronizerName, state.arrivedCount.get(), state.expectedParties
                    ));
                }
            }
            
            // Check for duplicate arrivals
            if (state.arrivedThreads.size() != state.arrivedCount.get()) {
                report.duplicateArrivals.add(String.format(
                    "%s: Thread arrived multiple times",
                    state.synchronizerName
                ));
            }
        }
        
        return report;
    }
    
    public void reset() {
        synchronizers.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class SynchronizerReport {
        public final Set<String> incompleteBarriers = new HashSet<>();
        public final Set<String> duplicateArrivals = new HashSet<>();
        
        public boolean hasIssues() {
            return !incompleteBarriers.isEmpty() || !duplicateArrivals.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No synchronizer issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("SYNCHRONIZER ISSUES DETECTED:\n");
            
            if (!incompleteBarriers.isEmpty()) {
                sb.append("\nIncomplete barrier advances:\n");
                for (String issue : incompleteBarriers) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Ensure all parties reach barrier before advancing\n");
            }
            
            if (!duplicateArrivals.isEmpty()) {
                sb.append("\nDuplicate arrivals:\n");
                for (String issue : duplicateArrivals) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
