package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        final Map<Long, Integer> lastGenerationByThread = new ConcurrentHashMap<>();
        final AtomicInteger duplicateArrivals = new AtomicInteger(0);
        
        BarrierState(String name, int parties) {
            this.synchronizerName = name;
            this.expectedParties = parties;
        }
    }
    
    private final Map<IdentityKey, BarrierState> synchronizers = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Register a synchronizer for monitoring.
     *
     * @param synchronizer the synchronizer being recorded, tracked by identity
     * @param expectedParties the number of parties expected to arrive
     */
    public void registerSynchronizer(Object synchronizer, int expectedParties) {
        if (!enabled || synchronizer == null) return;
        
        synchronizers.putIfAbsent(new IdentityKey(synchronizer), new BarrierState(
            synchronizer.getClass().getSimpleName(), 
            expectedParties
        ));
    }
    
    /**
     * Record thread arriving at barrier.
     *
     * @param synchronizer the synchronizer being recorded, tracked by identity
     */
    public void recordBarrierArrival(Object synchronizer) {
        if (!enabled || synchronizer == null) return;
        
        BarrierState state = synchronizers.get(new IdentityKey(synchronizer));
        if (state == null) return;
        
        long threadId = Thread.currentThread().threadId();
        int count = state.arrivedCount.incrementAndGet();
        // The barrier trips every expectedParties arrivals and is reused for the next generation;
        // the runner reuses its pool threads across rounds, so the same thread arriving again is
        // only a defect within one generation.
        int generation = state.expectedParties > 0 ? (count - 1) / state.expectedParties : 0;
        Integer previous = state.lastGenerationByThread.put(threadId, generation);
        if (previous != null && previous == generation) {
            state.duplicateArrivals.incrementAndGet();
        }
    }
    
    /**
     * Record thread advancing past barrier.
     *
     * @param synchronizer the synchronizer being recorded, tracked by identity
     */
    public void recordBarrierAdvance(Object synchronizer) {
        // Advancing past the barrier carries no signal the analysis uses: arrivals alone decide
        // both findings. The method stays because it is public API.
    }
    
    /**
     * Record barrier reset.
     *
     * @param synchronizer the synchronizer being recorded, tracked by identity
     */
    public void recordBarrierReset(Object synchronizer) {
        if (!enabled || synchronizer == null) return;
        
        BarrierState state = synchronizers.get(new IdentityKey(synchronizer));
        if (state == null) return;
        
        state.arrivedCount.set(0);
        state.lastGenerationByThread.clear();
    }
    
    /**
     * Analyze synchronizer behavior.
     *
     * @return the findings this detector collected during the run
     */
    public SynchronizerReport analyzeSynchronizers() {
        SynchronizerReport report = new SynchronizerReport();
        
        for (BarrierState state : synchronizers.values()) {
            // Check for partial arrivals
            int count = state.arrivedCount.get();
            if (count > 0 && state.expectedParties > 0 && count % state.expectedParties != 0) {
                report.incompleteBarriers.add(String.format(
                    "%s: %d/%d parties arrived",
                    state.synchronizerName, count % state.expectedParties, state.expectedParties
                ));
            }
            
            // Check for duplicate arrivals
            if (state.duplicateArrivals.get() > 0) {
                report.duplicateArrivals.add(String.format(
                    "%s: Thread arrived multiple times",
                    state.synchronizerName
                ));
            }
        }
        
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeSynchronizers()}.
     *
     * @return the findings this detector collected during the run
     */
    public SynchronizerReport analyze() {
        return analyzeSynchronizers();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        synchronizers.clear();
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
    
    public static class SynchronizerReport {
        /** Barriers that never had all their parties arrive. */
        public final Set<String> incompleteBarriers = new HashSet<>();
        /** Parties that arrived at a synchronizer more than once in a cycle. */
        public final Set<String> duplicateArrivals = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
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
