package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects memory ordering violations and compiler reordering issues.
 * 
 * Problems detected:
 * - Writes visible in wrong order
 * - Reads see stale values
 * - Reordering causes incorrect synchronization
 */
public class MemoryOrderingMonitor {
    
    private static class MemoryAccess {
        final long timestamp;
        final long threadId;
        final String operation;  // READ or WRITE
        final String location;
        final Object value;
        
        MemoryAccess(long tid, String op, String loc, Object val) {
            this.timestamp = System.nanoTime();
            this.threadId = tid;
            this.operation = op;
            this.location = loc;
            this.value = val;
        }
    }
    
    private final List<MemoryAccess> accessLog = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean enabled = true;
    
    /**
     * Record a memory read.
     */
    public void recordRead(String location, Object value) {
        if (!enabled) return;
        accessLog.add(new MemoryAccess(Thread.currentThread().getId(), "READ", location, value));
    }
    
    /**
     * Record a memory write.
     */
    public void recordWrite(String location, Object value) {
        if (!enabled) return;
        accessLog.add(new MemoryAccess(Thread.currentThread().getId(), "WRITE", location, value));
    }
    
    /**
     * Analyze for memory ordering violations.
     */
    public MemoryOrderingReport analyzeOrdering() {
        MemoryOrderingReport report = new MemoryOrderingReport();
        
        Map<String, List<MemoryAccess>> locationAccesses = new HashMap<>();
        for (MemoryAccess access : accessLog) {
            locationAccesses.computeIfAbsent(access.location, k -> new ArrayList<>()).add(access);
        }
        
        // Detect potential violations
        for (List<MemoryAccess> accesses : locationAccesses.values()) {
            if (accesses.size() < 2) continue;
            
            // Look for read-after-write patterns from different threads
            for (int i = 0; i < accesses.size() - 1; i++) {
                MemoryAccess a1 = accesses.get(i);
                MemoryAccess a2 = accesses.get(i + 1);
                
                // If write followed by read from different thread
                if (a1.operation.equals("WRITE") && a2.operation.equals("READ") && 
                    a1.threadId != a2.threadId) {
                    
                    // Check if read saw the written value
                    if (!Objects.equals(a1.value, a2.value)) {
                        report.staleCoreads.add(String.format(
                            "%s: Write by T-%d (%s), read by T-%d (%s)",
                            a1.location, a1.threadId, a1.value, a2.threadId, a2.value
                        ));
                    }
                }
            }
        }
        
        // Detect reordering within same thread
        Map<Long, List<MemoryAccess>> threadAccesses = new HashMap<>();
        for (MemoryAccess access : accessLog) {
            threadAccesses.computeIfAbsent(access.threadId, k -> new ArrayList<>()).add(access);
        }
        
        for (List<MemoryAccess> accesses : threadAccesses.values()) {
            // Look for suspicious reordering patterns
            for (int i = 0; i < accesses.size() - 1; i++) {
                MemoryAccess write = accesses.get(i);
                if (!write.operation.equals("WRITE")) continue;
                
                // If write is followed by unrelated operations
                for (int j = i + 1; j < accesses.size() && j < i + 3; j++) {
                    MemoryAccess next = accesses.get(j);
                    if (!next.location.equals(write.location)) {
                        // Potential reordering: write followed by unrelated op
                        report.suspiciousReorderings.add(String.format(
                            "T-%d: Write to %s followed by access to %s (possible reordering)",
                            write.threadId, write.location, next.location
                        ));
                        break;
                    }
                }
            }
        }
        
        return report;
    }
    
    public void reset() {
        accessLog.clear();
    }
    
    public void disable() {
        enabled = false;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public static class MemoryOrderingReport {
        public final Set<String> staleCoreads = new HashSet<>();
        public final Set<String> suspiciousReorderings = new HashSet<>();
        
        public boolean hasIssues() {
            return !staleCoreads.isEmpty() || !suspiciousReorderings.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No memory ordering violations detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("MEMORY ORDERING ISSUES DETECTED:\n");
            
            if (!staleCoreads.isEmpty()) {
                sb.append("\nStale reads:\n");
                for (String issue : staleCoreads) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Use volatile or synchronization\n");
            }
            
            if (!suspiciousReorderings.isEmpty()) {
                sb.append("\nSuspicious reorderings:\n");
                for (String issue : suspiciousReorderings) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Use volatile or memory barriers\n");
            }
            
            return sb.toString();
        }
    }
}
