package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        final long threadId;
        final String operation;  // READ or WRITE
        final String location;
        final Object value;

        MemoryAccess(long tid, String op, String loc, Object val) {
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
        accessLog.add(new MemoryAccess(Thread.currentThread().threadId(), "READ", location, value));
    }
    
    /**
     * Record a memory write.
     */
    public void recordWrite(String location, Object value) {
        if (!enabled) return;
        accessLog.add(new MemoryAccess(Thread.currentThread().threadId(), "WRITE", location, value));
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
                if ("WRITE".equals(a1.operation) && "READ".equals(a2.operation) && 
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
        
        // There was a second rule here that flagged any thread which wrote one location and
        // then touched a different one within the next two operations. That is ordinary code —
        // `a = 1; b = 2;` — and it counted toward hasIssues(), so every instrumented method that
        // touched two fields produced a violation.
        //
        // It was also unsound in principle, not merely too eager: accessLog records each thread's
        // own program order, and a reordering is by definition only observable from ANOTHER
        // thread seeing writes land out of order. A per-thread log cannot witness one. The stale
        // co-read check above is the signal that can, and it stays.
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeOrdering()}.
     */
    public MemoryOrderingReport analyze() {
        return analyzeOrdering();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */

    public void reset() {
        accessLog.clear();
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
    
    public static class MemoryOrderingReport {
        /** Reads that did not see a value another thread had written — a real visibility bug. */
        public final Set<String> staleCoreads = new HashSet<>();
        /**
         * Retained for source and binary compatibility, and still honoured by {@link #hasIssues()}
         * and {@link #toString()} so a caller can populate it.
         *
         * <p>Nothing in this monitor writes to it any more: the heuristic that did — "a write
         * followed by a touch of some other location" — fired on ordinary code such as
         * {@code a = 1; b = 2;}, and could not have been sound anyway. A reordering is only
         * observable from another thread seeing writes land out of order, which a per-thread
         * access log cannot witness. {@link #staleCoreads} is the check that can.
         */
        public final Set<String> suspiciousReorderings = new HashSet<>();
        
        /** {@return whether there are issues} */
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
