package se.deversity.asynctest.diagnostics;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heuristic for False Sharing - multiple threads accessing adjacent memory locations
 * that fall within the same CPU cache line (typically 64 bytes).
 *
 * <p><strong>Experimental, findings off by default.</strong> Cache-line effects are not
 * observable from pure Java: this detector estimates field offsets by summing nominal
 * type sizes in declaration order, while the JVM reorders fields, compresses references,
 * and honors {@code @Contended} padding, so the estimated offsets do not correspond to
 * real memory layout. Keying is per class rather than per object, so thread-confined
 * instances of one class are indistinguishable from a genuinely shared instance. The
 * findings are therefore not evidence of false sharing, and {@link #analyze()} returns
 * an empty report unless {@link #EXPERIMENTAL_PROPERTY} is set.
 * 
 * False sharing causes cache coherency traffic and performance degradation.
 * This detector identifies fields accessed by different threads with adjacent memory offsets.
 */
public class FalseSharingDetector {
    
    /**
     * System property that opts in to this detector's findings
     * ({@code -Dasync-test.experimental.false-sharing=true}). Without it, {@link #analyze()}
     * returns an empty report: the offset model behind the findings is declaration-order
     * arithmetic that real JVM field layout (reordering, compressed oops, {@code @Contended}
     * padding) does not follow, so the pairs it names are not evidence of actual cache-line
     * sharing. Recording is unaffected by the property.
     */
    public static final String EXPERIMENTAL_PROPERTY = "async-test.experimental.false-sharing";

    private static final int CACHE_LINE_SIZE = 64; // Common cache line size
    private static final int FIELD_ACCESS_THRESHOLD = 100; // Accesses to trigger analysis
    
    private final Map<String, FieldAccessInfo> fieldAccess = new ConcurrentHashMap<>();
    private final Map<String, List<AccessEvent>> accessHistory = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    private static class FieldAccessInfo {
        final String fieldName;
        final long memoryOffset;
        final AtomicLong accessCount = new AtomicLong(0);
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();

        FieldAccessInfo(String name, long offset) {
            this.fieldName = name;
            this.memoryOffset = offset;
        }
    }

    private static class AccessEvent {
        final long threadId;

        AccessEvent(long threadId) {
            this.threadId = threadId;
        }
    }
    
    /**
     * Record a field access. Call this when a field is accessed in your test.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     * @param fieldType the declared type of the field
     */
    public void recordFieldAccess(Object object, String fieldName, Class<?> fieldType) {
        if (!enabled || object == null) return;
        
        String key = object.getClass().getName() + "." + fieldName;

        // Try to estimate memory offset (this is approximate)
        long offset = estimateMemoryOffset(object.getClass(), fieldName);

        FieldAccessInfo info = fieldAccess.computeIfAbsent(key,
            k -> new FieldAccessInfo(fieldName, offset)
        );

        info.accessCount.incrementAndGet();
        info.accessingThreadIds.add(Thread.currentThread().threadId());

        // Record detailed access history for analysis
        accessHistory.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(new AccessEvent(Thread.currentThread().threadId()));
    }
    
    /**
     * Analyze for false sharing patterns.
     *
     * @return the findings this detector collected during the run
     */
    public FalseSharingReport analyzeFalseSharing() {
        FalseSharingReport report = new FalseSharingReport();
        
        // Findings are opt-in (see EXPERIMENTAL_PROPERTY): the offsets below are estimates
        // the JVM's real field layout does not follow, so without explicit opt-in the
        // detector must stay silent rather than report pairs it cannot substantiate.
        // Recording still ran, so setting the property and re-analyzing needs no re-run.
        if (!Boolean.getBoolean(EXPERIMENTAL_PROPERTY)) {
            return report;
        }

        List<FieldAccessInfo> fields = new ArrayList<>(fieldAccess.values());
        
        // Find fields in same cache line accessed by different threads
        for (int i = 0; i < fields.size(); i++) {
            FieldAccessInfo field1 = fields.get(i);
            if (field1.accessingThreadIds.size() < 2) continue;
            
            for (int j = i + 1; j < fields.size(); j++) {
                FieldAccessInfo field2 = fields.get(j);
                
                // Check if fields are in same cache line
                long offset1 = field1.memoryOffset;
                long offset2 = field2.memoryOffset;
                
                if (offset1 >= 0 && offset2 >= 0) {
                    long distance = Math.abs(offset1 - offset2);
                    
                    if (distance < CACHE_LINE_SIZE && distance > 0) {
                        // Different threads accessing adjacent fields
                        if (!field1.accessingThreadIds.equals(field2.accessingThreadIds)) {
                            FalseSharingReport.ContentionPair pair = new FalseSharingReport.ContentionPair(
                                field1.fieldName, field2.fieldName, distance,
                                field1.accessCount.get(), field2.accessCount.get()
                            );
                            report.falseSharedPairs.add(pair);
                        }
                    }
                }
            }
        }
        
        // Analyze contention patterns from history
        analyzeContentionPatterns(report);

        return report;
    }

    /**
     * Standardized alias for {@link #analyzeFalseSharing()}.
     *
     * @return the findings this detector collected during the run
     */
    public FalseSharingReport analyze() {
        return analyzeFalseSharing();
    }

    private void analyzeContentionPatterns(FalseSharingReport report) {
        for (Map.Entry<String, List<AccessEvent>> entry : accessHistory.entrySet()) {
            List<AccessEvent> history = entry.getValue();
            if (history.size() < FIELD_ACCESS_THRESHOLD) continue;
            
            // Check for high-frequency access to adjacent fields
            List<AccessEvent> snapshot;
            synchronized (history) {
                snapshot = new ArrayList<>(history);
            }
            Map<Long, Integer> threadAccessCounts = new HashMap<>();
            for (AccessEvent event : snapshot) {
                threadAccessCounts.merge(event.threadId, 1, Integer::sum);
            }
            
            if (threadAccessCounts.size() > 1) {
                int maxAccesses = threadAccessCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                if (maxAccesses > FIELD_ACCESS_THRESHOLD / 2) {
                    report.highContentionFields.add(entry.getKey());
                }
            }
        }
    }
    
    private long estimateMemoryOffset(Class<?> clazz, String fieldName) {
        try {
            Field target = clazz.getDeclaredField(fieldName);
            // Approximate offset based on field declaration order
            Field[] fields = clazz.getDeclaredFields();
            long offset = 16; // Object header
            for (Field f : fields) {
                if (f.equals(target)) {
                    return offset;
                }
                offset += getFieldSize(f.getType());
            }
            return -1;
        } catch (NoSuchFieldException e) {
            return -1;
        }
    }
    
    private long getFieldSize(Class<?> type) {
        if (type == long.class || type == double.class) return 8;
        if (type == int.class || type == float.class) return 4;
        if (type == short.class || type == char.class) return 2;
        if (type == byte.class || type == boolean.class) return 1;
        return 8; // References
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        fieldAccess.clear();
        accessHistory.clear();
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
    
    public static class FalseSharingReport {
        public static class ContentionPair {
            /** First field of the contending pair. */
            public final String field1;
            /** Second field of the contending pair. */
            public final String field2;
            /** Distance between the two fields; under a cache line means they share one. */
            public final long distanceInBytes;
            /** How many times the first field of the pair was accessed. */
            public final long accesses1;
            /** How many times the second field of the pair was accessed. */
            public final long accesses2;
            /**
             * Creates a ContentionPair.
             *
             * @param f1 the first field of the contending pair
             * @param f2 the second field of the contending pair
             * @param dist the distance between the two fields in bytes; under a cache line means they share one
             * @param acc1 how many times the first field was accessed
             * @param acc2 how many times the second field was accessed
             */
            public ContentionPair(String f1, String f2, long dist, long acc1, long acc2) {
                this.field1 = f1;
                this.field2 = f2;
                this.distanceInBytes = dist;
                this.accesses1 = acc1;
                this.accesses2 = acc2;
            }
        }
        
        /** Field pairs close enough to share a cache line and written from different threads. */
        public final Set<ContentionPair> falseSharedPairs = new HashSet<>();
        /** Fields written often enough for cache-line sharing to matter. */
        public final Set<String> highContentionFields = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !falseSharedPairs.isEmpty() || !highContentionFields.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No false sharing detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            // The severity marker is load-bearing, not decoration. IssueSeverity.fromReport
            // recovers a finding's severity from this text and defaults to HIGH when it finds no
            // marker, so an unmarked advisory about cache-line adjacency reached the failOn gate
            // ranked alongside a lost update. This detector is experimental, off unless
            // -Dasync-test.experimental.false-sharing=true, and its findings are documented as
            // uncorrelated with the phenomenon, so LOW is the only defensible ranking.
            sb.append(IssueSeverity.LOW.getLabel()).append(" ");
            sb.append("POTENTIAL FALSE SHARING DETECTED:\n");
            
            if (!falseSharedPairs.isEmpty()) {
                sb.append("\nFields in same cache line accessed by different threads:\n");
                for (ContentionPair pair : falseSharedPairs) {
                    sb.append(String.format(
                        "  - %s (accesses: %d) <-> %s (accesses: %d) [distance: %d bytes]%n",
                        pair.field1, pair.accesses1, pair.field2, pair.accesses2, pair.distanceInBytes
                    ));
                }
            }

            if (!highContentionFields.isEmpty()) {
                sb.append("\nHigh-contention fields accessed by multiple threads:\n");
                for (String field : highContentionFields) {
                    sb.append("  - ").append(field).append("\n");
                }
            }

            sb.append("\nWhy: CPUs transfer memory in 64-byte cache lines. When Thread A writes fieldA and Thread B writes fieldB and both fields occupy the same cache line, every write forces the entire line to be invalidated and re-fetched across all cores — \"cache ping-pong\" that can reduce throughput by 10x even though the threads are touching entirely different fields.\n");
            sb.append("Fix:\n");
            sb.append("  - Annotate each hot field with @Contended (sun.misc.Contended / jdk.internal.vm.annotation.Contended); add -XX:+EnableContended on Java 8-10 (default from Java 11 onward)\n");
            sb.append("  - Pad manually: place 7 long dummy fields between the hot fields to force them onto separate cache lines\n");
            sb.append("  - Redesign: group read-only fields together and isolate write-heavy fields in their own inner class annotated @Contended");
            
            return sb.toString();
        }
    }
}
