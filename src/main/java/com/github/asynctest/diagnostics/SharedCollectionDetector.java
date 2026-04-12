package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects non-thread-safe collections shared across multiple threads without
 * synchronization.
 *
 * <p>The following standard-library collections are <strong>not thread-safe</strong>
 * and will produce data corruption or {@link java.util.ConcurrentModificationException}
 * when mutated concurrently:
 * <ul>
 *   <li>{@code java.util.ArrayList}</li>
 *   <li>{@code java.util.HashMap} / {@code java.util.LinkedHashMap}</li>
 *   <li>{@code java.util.HashSet} / {@code java.util.LinkedHashSet}</li>
 *   <li>{@code java.util.LinkedList}</li>
 *   <li>{@code java.util.TreeMap} / {@code java.util.TreeSet}</li>
 *   <li>{@code java.util.ArrayDeque}</li>
 * </ul>
 *
 * <p>Common issues detected:
 * <ul>
 *   <li>Write operations ({@code add}, {@code put}, {@code remove}) from multiple threads</li>
 *   <li>Mixed read-write access without any synchronisation visible to the detector</li>
 *   <li>Structural modifications during concurrent reads</li>
 * </ul>
 *
 * <p>Thread-safe alternatives:
 * <ul>
 *   <li>{@code java.util.concurrent.ConcurrentHashMap}</li>
 *   <li>{@code java.util.concurrent.CopyOnWriteArrayList} (read-heavy workloads)</li>
 *   <li>{@code java.util.Collections.synchronizedList/Map/Set(...)}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectSharedCollections = true)
 * void testSharedList() {
 *     List<String> shared = new ArrayList<>();
 *     AsyncTestContext.sharedCollectionMonitor()
 *         .registerCollection(shared, "item-list", "ArrayList");
 *
 *     shared.add("item");
 *     AsyncTestContext.sharedCollectionMonitor()
 *         .recordWrite(shared, "item-list", "add");
 * }
 * }</pre>
 */
public class SharedCollectionDetector {

    private static class CollectionState {
        final String name;
        final String collectionType;
        final AtomicInteger readCount  = new AtomicInteger(0);
        final AtomicInteger writeCount = new AtomicInteger(0);
        final Set<Long> readThreads    = ConcurrentHashMap.newKeySet();
        final Set<Long> writeThreads   = ConcurrentHashMap.newKeySet();

        CollectionState(String name, String collectionType) {
            this.name = name;
            this.collectionType = collectionType;
        }
    }

    private final Map<Integer, CollectionState> collections = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a collection for monitoring.
     *
     * @param collection     the collection instance
     * @param name           a descriptive label used in reports
     * @param collectionType the concrete type, e.g. "ArrayList" or "HashMap"
     */
    public void registerCollection(Object collection, String name, String collectionType) {
        if (!enabled || collection == null) return;
        int key = System.identityHashCode(collection);
        String resolvedType = collectionType != null ? collectionType : collection.getClass().getSimpleName();
        String resolvedName = name != null ? name : resolvedType + "@" + key;
        collections.putIfAbsent(key, new CollectionState(resolvedName, resolvedType));
    }

    /**
     * Record a read operation ({@code get}, {@code contains}, {@code size}, iteration).
     *
     * @param collection the collection instance
     * @param name       the label (should match registration)
     * @param operation  a short name for the operation, e.g. {@code "get"}
     */
    public void recordRead(Object collection, String name, String operation) {
        if (!enabled || collection == null) return;
        CollectionState state = resolveState(collection, name);
        state.readThreads.add(Thread.currentThread().threadId());
        state.readCount.incrementAndGet();
    }

    /**
     * Record a write operation ({@code add}, {@code put}, {@code remove}, {@code clear}).
     *
     * @param collection the collection instance
     * @param name       the label (should match registration)
     * @param operation  a short name for the operation, e.g. {@code "add"}
     */
    public void recordWrite(Object collection, String name, String operation) {
        if (!enabled || collection == null) return;
        CollectionState state = resolveState(collection, name);
        state.writeThreads.add(Thread.currentThread().threadId());
        state.writeCount.incrementAndGet();
    }

    private CollectionState resolveState(Object collection, String name) {
        int key = System.identityHashCode(collection);
        return collections.computeIfAbsent(key, k -> {
            String type = collection.getClass().getSimpleName();
            String label = name != null ? name : type + "@" + k;
            return new CollectionState(label, type);
        });
    }

    /**
     * Analyse collection usage and return a report.
     */
    public SharedCollectionReport analyze() {
        SharedCollectionReport report = new SharedCollectionReport();

        for (CollectionState state : collections.values()) {
            Set<Long> allWriters = state.writeThreads;
            Set<Long> allReaders = state.readThreads;

            if (allWriters.size() > 1) {
                report.concurrentWriteViolations.add(String.format(
                        "%s (%s): write operations from %d threads (writes: %d) — DATA CORRUPTION RISK!",
                        state.name, state.collectionType,
                        allWriters.size(), state.writeCount.get()));
            } else if (allWriters.size() == 1 && allReaders.size() > 1) {
                // One writer, multiple readers — still risky without synchronisation
                report.mixedAccessViolations.add(String.format(
                        "%s (%s): written by 1 thread, read by %d threads without visible synchronisation — VISIBILITY RISK",
                        state.name, state.collectionType, allReaders.size()));
            }

            int total = state.readCount.get() + state.writeCount.get();
            if (total > 0) {
                report.collectionActivity.put(state.name, String.format(
                        "reads: %d from %d thread(s), writes: %d from %d thread(s)",
                        state.readCount.get(), allReaders.size(),
                        state.writeCount.get(), allWriters.size()));
            }
        }

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class SharedCollectionReport {

        final java.util.List<String> concurrentWriteViolations = new java.util.ArrayList<>();
        final java.util.List<String> mixedAccessViolations     = new java.util.ArrayList<>();
        final Map<String, String>    collectionActivity        = new ConcurrentHashMap<>();

        /** Returns {@code true} when any concurrent-access violations were detected. */
        public boolean hasIssues() {
            return !concurrentWriteViolations.isEmpty() || !mixedAccessViolations.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SHARED COLLECTION ISSUES DETECTED:\n");

            if (!concurrentWriteViolations.isEmpty()) {
                sb.append("  Concurrent Write Violations (data corruption risk):\n");
                for (String v : concurrentWriteViolations) {
                    sb.append("    - ").append(v).append("\n");
                }
            }

            if (!mixedAccessViolations.isEmpty()) {
                sb.append("  Mixed Read-Write Violations (visibility risk):\n");
                for (String v : mixedAccessViolations) {
                    sb.append("    - ").append(v).append("\n");
                }
            }

            if (!collectionActivity.isEmpty()) {
                sb.append("  Collection Activity:\n");
                for (Map.Entry<String, String> e : collectionActivity.entrySet()) {
                    sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use ConcurrentHashMap, CopyOnWriteArrayList, or synchronizedList/Map/Set");
            return sb.toString();
        }
    }
}
