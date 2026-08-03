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
 * Detects False Sharing - when multiple threads access adjacent memory locations
 * that fall within the same CPU cache line (typically 64 bytes).
 * 
 * False sharing causes cache coherency traffic and performance degradation.
 * This detector identifies fields accessed by different threads with adjacent memory offsets.
 */
public class FalseSharingDetector {
    
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
     */
    public FalseSharingReport analyzeFalseSharing() {
        FalseSharingReport report = new FalseSharingReport();
        
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
     */
    public FalseSharingReport analyze() {
        return analyzeFalseSharing();
    }

    private void analyzeContentionPatterns(FalseSharingReport report) {
        for (Map.Entry<String, List<AccessEvent>> entry : accessHistory.entrySet()) {
            List<AccessEvent> history = entry.getValue();
            if (history.size() < FIELD_ACCESS_THRESHOLD) continue;
            
            // Check for high-frequency access to adjacent fields
            Map<Long, Integer> threadAccessCounts = new HashMap<>();
            for (AccessEvent event : history) {
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
    
    public void reset() {
        fieldAccess.clear();
        accessHistory.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class FalseSharingReport {
        public static class ContentionPair {
            /** The field 1. */
            public final String field1;
            /** The field 2. */
            public final String field2;
            /** The distance in bytes. */
            public final long distanceInBytes;
            /** The accesses 1. */
            public final long accesses1;
            /** The accesses 2. */
            public final long accesses2;
            
            public ContentionPair(String f1, String f2, long dist, long acc1, long acc2) {
                this.field1 = f1;
                this.field2 = f2;
                this.distanceInBytes = dist;
                this.accesses1 = acc1;
                this.accesses2 = acc2;
            }
        }
        
        /** The false shared pairs. */
        public final Set<ContentionPair> falseSharedPairs = new HashSet<>();
        /** The high contention fields. */
        public final Set<String> highContentionFields = new HashSet<>();
        
        /** {@return whether there are issues} */
        public boolean hasIssues() {
            return !falseSharedPairs.isEmpty() || !highContentionFields.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No false sharing detected.";
            }
            
            StringBuilder sb = new StringBuilder();
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
