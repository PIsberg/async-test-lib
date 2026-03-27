package com.github.asynctest.diagnostics;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects concurrent modification issues in collections during iteration.
 * 
 * Common concurrent modification issues detected:
 * - Fail-fast iterator violations: Collection modified while iterating
 * - Missing ConcurrentModificationException in expected scenarios
 * - Unsafe iteration over non-thread-safe collections
 * - Structural modifications during iteration without using Iterator.remove()
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectConcurrentModifications = true)
 * void testCollectionIteration() {
 *     List<String> list = new ArrayList<>();
 *     AsyncTestContext.concurrentModificationMonitor()
 *         .registerCollection(list, "shared-list");
 *     
 *     // Track iteration
 *     Iterator<String> it = list.iterator();
 *     AsyncTestContext.concurrentModificationMonitor()
 *         .recordIterationStarted(list, "shared-list");
 *     
 *     while (it.hasNext()) {
 *         String item = it.next();
 *         // Bug: modifying collection during iteration!
 *         AsyncTestContext.concurrentModificationMonitor()
 *             .recordModificationDuringIteration(list, "shared-list", "add");
 *         list.add("new-item");
 *     }
 * }
 * }</pre>
 */
public class ConcurrentModificationDetector {

    private static class CollectionState {
        final String name;
        final Collection<?> collection;
        final AtomicInteger modificationCount = new AtomicInteger(0);
        final AtomicInteger activeIterators = new AtomicInteger(0);
        final AtomicInteger concurrentModifications = new AtomicInteger(0);
        final Set<Long> iteratingThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> allIteratingThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> modifyingThreads = ConcurrentHashMap.newKeySet();
        volatile String lastModificationType = "none";

        CollectionState(Collection<?> collection, String name) {
            this.collection = collection;
            this.name = name != null ? name : "collection@" + System.identityHashCode(collection);
        }
    }

    private final Map<Integer, CollectionState> collections = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a collection for monitoring.
     * 
     * @param collection the collection to monitor
     * @param name a descriptive name for reporting
     */
    public void registerCollection(Collection<?> collection, String name) {
        if (!enabled || collection == null) {
            return;
        }
        collections.put(System.identityHashCode(collection), 
            new CollectionState(collection, name));
    }

    /**
     * Record that an iteration has started on a collection.
     * 
     * @param collection the collection being iterated
     * @param name the collection name (should match registration)
     */
    public void recordIterationStarted(Collection<?> collection, String name) {
        if (!enabled || collection == null) {
            return;
        }
        CollectionState state = collections.get(System.identityHashCode(collection));
        if (state != null) {
            state.activeIterators.incrementAndGet();
            state.iteratingThreads.add(Thread.currentThread().threadId());
            state.allIteratingThreads.add(Thread.currentThread().threadId());
        }
    }

    /**
     * Record that an iteration has ended on a collection.
     * 
     * @param collection the collection being iterated
     * @param name the collection name (should match registration)
     */
    public void recordIterationEnded(Collection<?> collection, String name) {
        if (!enabled || collection == null) {
            return;
        }
        CollectionState state = collections.get(System.identityHashCode(collection));
        if (state != null) {
            state.activeIterators.decrementAndGet();
            state.iteratingThreads.remove(Thread.currentThread().threadId());
        }
    }

    /**
     * Record a structural modification to a collection.
     * 
     * @param collection the collection being modified
     * @param name the collection name (should match registration)
     * @param modificationType the type of modification (add, remove, clear, etc.)
     */
    public void recordModification(Collection<?> collection, String name, String modificationType) {
        if (!enabled || collection == null) {
            return;
        }
        CollectionState state = collections.get(System.identityHashCode(collection));
        if (state != null) {
            state.modificationCount.incrementAndGet();
            state.modifyingThreads.add(Thread.currentThread().threadId());
            state.lastModificationType = modificationType;
            
            // Check if modification happened during iteration
            if (state.activeIterators.get() > 0) {
                state.concurrentModifications.incrementAndGet();
            }
        }
    }

    /**
     * Record a modification that happened during iteration (detected ConcurrentModificationException).
     * 
     * @param collection the collection being modified
     * @param name the collection name (should match registration)
     * @param modificationType the type of modification
     */
    public void recordModificationDuringIteration(Collection<?> collection, String name, String modificationType) {
        if (!enabled || collection == null) {
            return;
        }
        CollectionState state = collections.get(System.identityHashCode(collection));
        if (state != null) {
            state.concurrentModifications.incrementAndGet();
            state.modifyingThreads.add(Thread.currentThread().threadId());
            state.lastModificationType = modificationType;
        }
    }

    /**
     * Analyze collection usage for concurrent modification issues.
     * 
     * @return a report of detected issues
     */
    public ConcurrentModificationReport analyze() {
        ConcurrentModificationReport report = new ConcurrentModificationReport();
        report.enabled = enabled;

        for (CollectionState state : collections.values()) {
            // Check for concurrent modifications during iteration
            if (state.concurrentModifications.get() > 0) {
                report.concurrentModifications.add(String.format(
                    "%s: %d modifications occurred during active iteration (last: %s)",
                    state.name, state.concurrentModifications.get(), state.lastModificationType));
            }

            // Check for multiple threads iterating same collection
            if (state.allIteratingThreads.size() > 1) {
                report.concurrentIterations.add(String.format(
                    "%s: %d threads performed iteration (potential race condition)",
                    state.name, state.allIteratingThreads.size()));
            }

            // Check for multiple threads modifying same collection
            if (state.modifyingThreads.size() > 1) {
                report.concurrentMutations.add(String.format(
                    "%s: %d threads performed modifications (%d total modifications)",
                    state.name, state.modifyingThreads.size(), state.modificationCount.get()));
            }

            // Track collection activity
            if (state.activeIterators.get() > 0 || state.modificationCount.get() > 0) {
                report.collectionActivity.put(state.name, String.format(
                    "iterations: %d, modifications: %d, concurrent mods: %d",
                    state.activeIterators.get(),
                    state.modificationCount.get(),
                    state.concurrentModifications.get()));
            }
        }

        return report;
    }

    /**
     * Report class for concurrent modification analysis.
     */
    public static class ConcurrentModificationReport {
        private boolean enabled = true;
        final java.util.List<String> concurrentModifications = new java.util.ArrayList<>();
        final java.util.List<String> concurrentIterations = new java.util.ArrayList<>();
        final java.util.List<String> concurrentMutations = new java.util.ArrayList<>();
        final Map<String, String> collectionActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !concurrentModifications.isEmpty() || !concurrentIterations.isEmpty() || !concurrentMutations.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ConcurrentModificationReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CONCURRENT MODIFICATION ISSUES DETECTED:\n");

            if (!concurrentModifications.isEmpty()) {
                sb.append("  Modifications During Iteration:\n");
                for (String issue : concurrentModifications) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!concurrentIterations.isEmpty()) {
                sb.append("  Concurrent Iterations:\n");
                for (String issue : concurrentIterations) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!concurrentMutations.isEmpty()) {
                sb.append("  Concurrent Mutations:\n");
                for (String issue : concurrentMutations) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!collectionActivity.isEmpty()) {
                sb.append("  Collection Activity:\n");
                for (Map.Entry<String, String> entry : collectionActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use Iterator.remove() for safe removal during iteration, or use thread-safe collections");
            return sb.toString();
        }
    }
}
