package com.github.asynctest.diagnostics;

import java.lang.reflect.Field;
import java.util.*;
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
    private final AtomicLong eventCounter = new AtomicLong(0);
    private volatile boolean enabled = true;
    
    private static class FieldAccessInfo {
        final String fieldName;
        final Class<?> fieldType;
        final long memoryOffset;
        volatile long accessCount = 0;
        volatile long lastAccessTime = 0;
        final Set<Long> accessingThreadIds = ConcurrentHashMap.newKeySet();
        
        FieldAccessInfo(String name, Class<?> type, long offset) {
            this.fieldName = name;
            this.fieldType = type;
            this.memoryOffset = offset;
        }
    }
    
    private static class AccessEvent {
        final long timestamp;
        final long threadId;
        final String fieldName;
        final long offset;
        
        AccessEvent(long timestamp, long threadId, String fieldName, long offset) {
            this.timestamp = timestamp;
            this.threadId = threadId;
            this.fieldName = fieldName;
            this.offset = offset;
        }
    }
    
    /**
     * Record a field access. Call this when a field is accessed in your test.
     */
    public void recordFieldAccess(Object object, String fieldName, Class<?> fieldType) {
        if (!enabled || object == null) return;
        
        try {
            String key = object.getClass().getName() + "." + fieldName;
            
            // Try to estimate memory offset (this is approximate)
            long offset = estimateMemoryOffset(object.getClass(), fieldName);
            
            FieldAccessInfo info = fieldAccess.computeIfAbsent(key, 
                k -> new FieldAccessInfo(fieldName, fieldType, offset)
            );
            
            info.accessCount++;
            info.lastAccessTime = System.nanoTime();
            info.accessingThreadIds.add(Thread.currentThread().getId());
            
            // Record detailed access history for analysis
            accessHistory.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new AccessEvent(System.nanoTime(), Thread.currentThread().getId(), fieldName, offset));
            
        } catch (Exception e) {
            // Silently ignore reflection errors
        }
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
                                field1.accessCount, field2.accessCount
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
            Field field = clazz.getDeclaredField(fieldName);
            // Try to get offset via Unsafe (Java 9+)
            // For now, return approximate based on field order
            Field[] fields = clazz.getDeclaredFields();
            long offset = 16; // Object header
            for (Field f : fields) {
                if (f.getName().equals(fieldName)) {
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
            public final String field1;
            public final String field2;
            public final long distanceInBytes;
            public final long accesses1;
            public final long accesses2;
            
            public ContentionPair(String f1, String f2, long dist, long acc1, long acc2) {
                this.field1 = f1;
                this.field2 = f2;
                this.distanceInBytes = dist;
                this.accesses1 = acc1;
                this.accesses2 = acc2;
            }
        }
        
        public final Set<ContentionPair> falseSharedPairs = new HashSet<>();
        public final Set<String> highContentionFields = new HashSet<>();
        
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
                        "  - %s (accesses: %d) <-> %s (accesses: %d) [distance: %d bytes]\n",
                        pair.field1, pair.accesses1, pair.field2, pair.accesses2, pair.distanceInBytes
                    ));
                }
                sb.append("\nFix: Add @Contended annotation or adjust field layout\n");
            }
            
            if (!highContentionFields.isEmpty()) {
                sb.append("\nHigh-contention fields (potential false sharing):\n");
                for (String field : highContentionFields) {
                    sb.append("  - ").append(field).append("\n");
                }
                sb.append("\nFix: Pad fields or use @Contended\n");
            }
            
            return sb.toString();
        }
    }
}
