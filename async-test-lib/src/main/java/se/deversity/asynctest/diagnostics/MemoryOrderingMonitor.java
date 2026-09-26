package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Flags a read that did not return the value of another thread's write recorded just before it.
 *
 * <p><strong>What it can claim.</strong> The log is ordered by when each record call was
 * appended, not by when the memory operations happened: a record is made after the read or
 * write it describes, by a thread that can be descheduled in between. So a write recorded before
 * a read does not mean the write happened before the read, and a read that returned something
 * else is consistent with a stale read (no happens-before edge from the write to the read) and
 * equally with a read that simply ran first. The finding says exactly that: these two records,
 * in this order, disagree. It is a prompt to check for a missing happens-before edge, never a
 * verdict, and the trust tier says so.
 *
 * <p>A read whose value a later-recorded write produced is not reported: that read saw a write
 * whose record had not landed yet, which is the log lagging, not the read being stale.
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
     *
     * @param location where in the code this happened, shown in the report
     * @param value the value read or written
     */
    public void recordRead(String location, Object value) {
        if (!enabled) return;
        accessLog.add(new MemoryAccess(Thread.currentThread().threadId(), "READ", location, value));
    }
    
    /**
     * Record a memory write.
     *
     * @param location where in the code this happened, shown in the report
     * @param value the value read or written
     */
    public void recordWrite(String location, Object value) {
        if (!enabled) return;
        accessLog.add(new MemoryAccess(Thread.currentThread().threadId(), "WRITE", location, value));
    }
    
    /**
     * Analyze for memory ordering violations.
     *
     * @return the findings this detector collected during the run
     */
    public MemoryOrderingReport analyzeOrdering() {
        MemoryOrderingReport report = new MemoryOrderingReport();
        
        Map<String, List<MemoryAccess>> locationAccesses = new HashMap<>();
        List<MemoryAccess> snapshot;
        synchronized (accessLog) {
            snapshot = new ArrayList<>(accessLog);
        }
        for (MemoryAccess access : snapshot) {
            locationAccesses.computeIfAbsent(access.location, k -> new ArrayList<>()).add(access);
        }
        
        // Detect potential violations
        for (List<MemoryAccess> accesses : locationAccesses.values()) {
            if (accesses.size() < 2) continue;
            
            // Look for read-after-write patterns from different threads, in record order
            for (int i = 0; i < accesses.size() - 1; i++) {
                MemoryAccess a1 = accesses.get(i);
                MemoryAccess a2 = accesses.get(i + 1);
                
                // If write followed by read from different thread
                if ("WRITE".equals(a1.operation) && "READ".equals(a2.operation) && 
                    a1.threadId != a2.threadId
                    // Check if read saw the written value, or one a later record explains
                    && !Objects.equals(a1.value, a2.value)
                    && !writtenByALaterRecord(accesses, i + 2, a2.value)) {
                    report.staleCoreads.add(String.format(Locale.ROOT,
                        "%s: T-%d's write of %s was recorded, then T-%d's read returned %s. "
                            + "Record order is not memory order: the read may have run before "
                            + "the write, or not seen it. If T-%d must see this write, give it a "
                            + "happens-before edge to the read",
                        a1.location, a1.threadId, a1.value, a2.threadId, a2.value, a2.threadId
                    ));
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

    /** Whether a write recorded at or after {@code from} wrote {@code value}. */
    private static boolean writtenByALaterRecord(List<MemoryAccess> accesses, int from, Object value) {
        for (int j = from; j < accesses.size(); j++) {
            MemoryAccess later = accesses.get(j);
            if ("WRITE".equals(later.operation) && Objects.equals(later.value, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Standardized alias for {@link #analyzeOrdering()}.
     *
     * @return the findings this detector collected during the run
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
        /**
         * Reads that did not return the value of another thread's write recorded just before
         * them. Record order is not memory order, so each is a prompt to check for a missing
         * happens-before edge, not proof of a visibility bug.
         */
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
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !staleCoreads.isEmpty() || !suspiciousReorderings.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No memory ordering violations detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.HIGH.format())
              .append(": MEMORY ORDERING: reads that disagree with the write recorded before them\n");
            
            if (!staleCoreads.isEmpty()) {
                sb.append("\nReads that did not return the preceding recorded write "
                        + "(record order, not memory order):\n");
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
