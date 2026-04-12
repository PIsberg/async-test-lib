package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects {@code CopyOnWriteArrayList} and {@code CopyOnWriteArraySet} used in
 * write-heavy concurrent scenarios where the copy-on-write overhead becomes a
 * significant performance bottleneck.
 *
 * <p>Copy-on-write (CoW) collections are correct for concurrent use, but they have
 * O(n) cost for <em>every write</em> because the entire backing array is copied.
 * This makes them suitable only for read-heavy workloads where writes are rare.
 *
 * <p>Issues detected:
 * <ul>
 *   <li>Write ratio exceeds a configurable threshold (default: 20 % of all operations)</li>
 *   <li>High absolute write count from multiple threads</li>
 *   <li>Mixture of concurrent reads and writes that may be better served by
 *       {@code ConcurrentHashMap} or a guarded {@code ArrayList}</li>
 * </ul>
 *
 * <p>Recommended alternatives when writes are frequent:
 * <ul>
 *   <li>{@code ConcurrentHashMap.newKeySet()} — O(1) add/remove/contains</li>
 *   <li>{@code Collections.synchronizedList(new ArrayList<>())} with careful iteration</li>
 *   <li>{@code ConcurrentLinkedQueue} for FIFO workloads</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectCopyOnWriteCollectionIssues = true)
 * void testCopyOnWriteUsage() {
 *     CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
 *     AsyncTestContext.copyOnWriteMonitor()
 *         .registerCollection(list, "event-list");
 *
 *     list.add("event");
 *     AsyncTestContext.copyOnWriteMonitor()
 *         .recordWrite(list, "event-list");
 * }
 * }</pre>
 */
public class CopyOnWriteCollectionDetector {

    /**
     * Fraction of total operations that are writes above which a warning is raised.
     * Default: 20 %.
     */
    private static final double WRITE_RATIO_THRESHOLD = 0.20;

    /**
     * Minimum number of writes before the ratio check fires, to avoid false positives
     * on collections with very few total accesses.
     */
    private static final int MIN_WRITE_COUNT_FOR_RATIO_CHECK = 5;

    private static class CoWState {
        final String name;
        final String collectionType;
        final AtomicInteger readCount  = new AtomicInteger(0);
        final AtomicInteger writeCount = new AtomicInteger(0);

        CoWState(String name, String collectionType) {
            this.name = name;
            this.collectionType = collectionType;
        }
    }

    private final Map<Integer, CoWState> collections = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Copy-on-Write collection for monitoring.
     *
     * @param collection     the {@code CopyOnWriteArrayList} or {@code CopyOnWriteArraySet}
     * @param name           a descriptive label for reports
     */
    public void registerCollection(Object collection, String name) {
        if (!enabled || collection == null) return;
        int key = System.identityHashCode(collection);
        String type = collection.getClass().getSimpleName();
        collections.putIfAbsent(key,
                new CoWState(name != null ? name : type + "@" + key, type));
    }

    /**
     * Record a read operation ({@code get}, {@code contains}, {@code size}, iteration).
     *
     * @param collection the collection instance
     * @param name       the label (should match registration)
     */
    public void recordRead(Object collection, String name) {
        if (!enabled || collection == null) return;
        resolve(collection, name).readCount.incrementAndGet();
    }

    /**
     * Record a write operation ({@code add}, {@code remove}, {@code set}, {@code clear}).
     *
     * @param collection the collection instance
     * @param name       the label (should match registration)
     */
    public void recordWrite(Object collection, String name) {
        if (!enabled || collection == null) return;
        resolve(collection, name).writeCount.incrementAndGet();
    }

    private CoWState resolve(Object collection, String name) {
        int key = System.identityHashCode(collection);
        return collections.computeIfAbsent(key, k -> {
            String type = collection.getClass().getSimpleName();
            return new CoWState(name != null ? name : type + "@" + k, type);
        });
    }

    /**
     * Analyse Copy-on-Write collection usage and return a report.
     */
    public CopyOnWriteReport analyze() {
        CopyOnWriteReport report = new CopyOnWriteReport();

        for (CoWState state : collections.values()) {
            int reads  = state.readCount.get();
            int writes = state.writeCount.get();
            int total  = reads + writes;
            if (total == 0) continue;

            report.totalCollections++;

            double writeRatio = (double) writes / total;

            if (writes >= MIN_WRITE_COUNT_FOR_RATIO_CHECK && writeRatio > WRITE_RATIO_THRESHOLD) {
                report.writeHeavyViolations.add(String.format(
                        "%s (%s): %.0f%% write ratio (%d writes, %d reads) — "
                        + "copy-on-write overhead is O(n) per write; consider ConcurrentHashMap.newKeySet() or ConcurrentLinkedQueue",
                        state.name, state.collectionType,
                        writeRatio * 100, writes, reads));
            }

            report.collectionActivity.put(state.name, String.format(
                    "reads: %d, writes: %d, write ratio: %.0f%%",
                    reads, writes, writeRatio * 100));
        }

        return report;
    }

    // ---- Report ----------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class CopyOnWriteReport {

        int totalCollections = 0;
        final java.util.List<String> writeHeavyViolations = new java.util.ArrayList<>();
        final Map<String, String>    collectionActivity   = new ConcurrentHashMap<>();

        /** Returns {@code true} when any write-heavy violations were detected. */
        public boolean hasIssues() {
            return !writeHeavyViolations.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("COPY-ON-WRITE COLLECTION ISSUES DETECTED:\n");

            if (!writeHeavyViolations.isEmpty()) {
                sb.append("  Write-Heavy Usage (performance bottleneck):\n");
                for (String v : writeHeavyViolations) {
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

            sb.append("  Fix: use ConcurrentHashMap.newKeySet() or ConcurrentLinkedQueue for write-heavy workloads");
            return sb.toString();
        }
    }
}
