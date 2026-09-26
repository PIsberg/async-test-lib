package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects the ABA Problem in atomic operations.
 * 
 * ABA Problem: 
 * 1. Thread A reads value X as A
 * 2. Thread B changes A -> B -> A (value is back to A)
 * 3. Thread A's CAS(X, A, C) succeeds, but X was modified!
 * 
 * This is a subtle bug in lock-free code that can cause:
 * - Data structure corruption
 * - Lost updates
 * - Incorrect synchronization
 *
 * <p><strong>What is a finding.</strong> Only that interleaving, in the order the events were
 * recorded: a thread records the read its compare-and-set expects
 * ({@link #recordRead(String, Object)}), <em>other</em> threads record a change away from that
 * value and a change back to it, and then the first thread records a successful
 * compare-and-set expecting the value it read. Record each event where it happens, on the
 * thread doing it; the detector orders events by when they were recorded and has no other
 * clock.
 *
 * <p>A value going A to B and back to A is not a finding on its own. One thread pushing and
 * then popping, a flag set and cleared, a counter incremented and decremented: each is an
 * A-B-A history, and none of them hurts a compare-and-set whose premise was read after the
 * toggle, or one taken by the thread that did the toggling. Such cycles are still counted in
 * {@link ABAReport#variablesWithCycles} and shown as context beside a finding.
 */
public class ABAProblemDetector {

    /** Record order across all variables; the only clock the interleaving check has. */
    private final AtomicLong sequence = new AtomicLong();

    private static class AtomicValueHistory {
        final String varName;
        /**
         * The latest recorded read per thread. A compare-and-set takes its premise from the read
         * just before it, so a later read replaces an earlier one, and a compare-and-set
         * consumes the read it was checked against.
         */
        final Map<Long, ValueRead> reads = new ConcurrentHashMap<>();
        /**
         * Guards {@link #changes}. A dedicated private lock rather than the list itself: this
         * class is extensible, so its fields are reachable by subclasses, and a lock a subclass
         * can also acquire is not a lock.
         */
        private final Object changesLock = new Object();
        /** Guarded by {@link #changesLock} — never touch it outside that monitor. */
        final List<ValueChange> changes = new ArrayList<>();
        final Map<Long, CASAttempt> casAttempts = new ConcurrentHashMap<>();
        final AtomicLong cycleCount = new AtomicLong(0);
        
        AtomicValueHistory(String name) {
            this.varName = name;
        }
    }
    
    private record ValueRead(long seq, Object value) { }

    private static class ValueChange {
        final Object oldValue;
        final Object newValue;
        final long seq;
        final long threadId;

        ValueChange(Object old, Object neu, long seq, long threadId) {
            this.oldValue = old;
            this.newValue = neu;
            this.seq = seq;
            this.threadId = threadId;
        }
        
        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"}) // identity equality intentional for atomic value tracking
        boolean isSameValue(Object v1, Object v2) {
            if (v1 == null && v2 == null) return true;
            if (v1 == null || v2 == null) return false;
            return v1.equals(v2) || v1 == v2;
        }
    }
    
    private static class CASAttempt {
        final Object expectedValue;
        final Object newValue;
        volatile boolean wasABA = false;

        CASAttempt(Object expected, Object neu) {
            this.expectedValue = expected;
            this.newValue = neu;
        }
    }
    
    private final Map<String, AtomicValueHistory> trackedVariables = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    /**
     * Record a value change in an atomic variable.
     *
     * @param variableName a label identifying the variable in the report
     * @param oldValue the value present before the write
     * @param newValue the value being written
     */
    public void recordValueChange(String variableName, Object oldValue, Object newValue) {
        if (!enabled) return;
        
        AtomicValueHistory history = trackedVariables.computeIfAbsent(variableName,
            AtomicValueHistory::new
        );
        
        long tid = Thread.currentThread().threadId();
        synchronized (history.changesLock) {
            // Sequence taken under the lock, so the list stays in record order.
            history.changes.add(new ValueChange(oldValue, newValue, sequence.incrementAndGet(), tid));
        }
        
        // Detect cycles (A -> B -> A pattern)
        detectCycles(history);
    }
    
    /**
     * Record the read a compare-and-set will take as its expected value, on the thread that
     * reads it: the {@code observed = ref.get()} at the top of a lock-free retry loop.
     *
     * <p>This is what places the premise in time. Without it the detector cannot tell a
     * compare-and-set whose expected value was read before another thread's A-B-A from one read
     * after it, and it draws no ABA verdict.
     *
     * @param variableName a label identifying the variable in the report
     * @param observedValue the value read
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordRead(String variableName, Object observedValue) {
        if (!enabled) return;

        AtomicValueHistory history = trackedVariables.computeIfAbsent(variableName,
            AtomicValueHistory::new
        );
        long seq;
        synchronized (history.changesLock) {
            // Under the changes lock so the read is ordered against changes on this variable.
            seq = sequence.incrementAndGet();
        }
        history.reads.put(Thread.currentThread().threadId(), new ValueRead(seq, observedValue));
    }

    /**
     * Record a CAS (Compare-And-Swap) attempt, on the thread that made it.
     *
     * <p>A successful attempt is an ABA finding when this thread's last recorded read of the
     * variable saw {@code expectedValue}, and after that read other threads recorded a change
     * away from it and a change back to it (see the class documentation).
     *
     * @param variableName a label identifying the variable in the report
     * @param expectedValue the value the compare-and-set expected to find
     * @param newValue the value being written
     * @param succeeded the {@code succeeded} flag
     * @param actualCurrentValue the value actually found, when it differed from the expected one
     */
    public void recordCASAttempt(String variableName, Object expectedValue, Object newValue, 
                                 boolean succeeded, Object actualCurrentValue) {
        if (!enabled) return;
        
        AtomicValueHistory history = trackedVariables.computeIfAbsent(variableName,
            AtomicValueHistory::new
        );
        
        CASAttempt attempt = new CASAttempt(expectedValue, newValue);
        long tid = Thread.currentThread().threadId();
        // The attempt consumes its premise: a retry reads again before it tries again.
        ValueRead premise = history.reads.remove(tid);

        // Detect ABA: the value moved away and came back while this thread held a stale read
        if (succeeded && premise != null && sameValue(premise.value(), expectedValue)
                && detectABA(history, attempt, premise.seq(), tid)) {
            attempt.wasABA = true;
        }
        
        history.casAttempts.put((long) System.identityHashCode(attempt), attempt);
    }
    
    /**
     * An ABA is a value coming back to what it just was: a change {@code A -> B} immediately
     * followed by {@code B -> A}.
     *
     * <p>Two changes are all it takes to see that, and two is all the canonical case produces —
     * a lock-free stack head sitting at A, swung to B, swung back to A. There is no {@code ? -> A}
     * change, because A is the value the variable <em>started</em> with, never one it was written
     * to. The previous implementation required three changes and matched a {@code ? -> A},
     * {@code A -> B}, {@code B -> A} window, so the minimal cycle fell straight through its
     * {@code size() < 3} guard and was never counted.
     *
     * <p>Only the newest pair is examined: each pair is therefore checked
     * exactly once, as it is formed, instead of the whole history being rescanned on every
     * change (which inflated the cycle count quadratically).
     */
    private void detectCycles(AtomicValueHistory history) {
        List<ValueChange> changes = history.changes;

        // Reading two elements consistently, while other threads append, needs the lock held
        // across both reads.
        synchronized (history.changesLock) {
            int size = changes.size();
            if (size < 2) return;

            ValueChange previous = changes.get(size - 2);   // A -> B
            ValueChange latest = changes.get(size - 1);     // B -> A ?

            boolean contiguous = latest.isSameValue(previous.newValue, latest.oldValue);
            boolean returnedToStart = latest.isSameValue(previous.oldValue, latest.newValue);
            boolean actuallyMoved = !latest.isSameValue(previous.oldValue, previous.newValue);

            if (contiguous && returnedToStart && actuallyMoved) {
                history.cycleCount.incrementAndGet();
            }
        }
    }
    
    /**
     * Whether, after the attempting thread's read at {@code readSeq}, another thread recorded a
     * change away from the expected value and a thread other than the attempting one then
     * recorded a change back to it.
     *
     * <p>The attempting thread's own changes do not count: a thread cannot be surprised by a
     * toggle it made itself, and its own compare-and-set is recorded as a change too.
     */
    private boolean detectABA(AtomicValueHistory history, CASAttempt attempt, long readSeq, long casThread) {
        List<ValueChange> changes = history.changes;
        boolean movedAway = false;
        // Walked under the lock: the list is appended to concurrently. Only the tail after the
        // read is visited, newest first until the read's position is passed.
        synchronized (history.changesLock) {
            int start = changes.size();
            while (start > 0 && changes.get(start - 1).seq > readSeq) {
                start--;
            }
            for (int i = start; i < changes.size(); i++) {
                ValueChange change = changes.get(i);
                if (change.threadId == casThread) {
                    continue;
                }
                if (!movedAway) {
                    movedAway = sameValue(change.oldValue, attempt.expectedValue)
                            && !sameValue(change.newValue, attempt.expectedValue);
                } else if (sameValue(change.newValue, attempt.expectedValue)) {
                    return true; // A -> B -> A behind this thread's back
                }
            }
        }
        return false;
    }

    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"}) // identity equality intentional for atomic value tracking
    private static boolean sameValue(Object v1, Object v2) {
        if (v1 == null && v2 == null) return true;
        if (v1 == null || v2 == null) return false;
        return v1.equals(v2) || v1 == v2;
    }
    
    /**
     * Analyze for ABA problems.
     *
     * @return the findings this detector collected during the run
     */
    public ABAReport analyzeABA() {
        ABAReport report = new ABAReport();
        
        for (AtomicValueHistory history : trackedVariables.values()) {
            long cycles = history.cycleCount.get();
            if (cycles > 0) {
                report.variablesWithCycles.put(history.varName, (int) cycles);
            }
            
            // Check for CAS attempts that succeeded despite ABA
            for (CASAttempt attempt : history.casAttempts.values()) {
                if (attempt.wasABA) {
                    report.successfulABACases.add(String.format(
                        "%s: CAS succeeded despite ABA (expected %s, set to %s)",
                        history.varName, attempt.expectedValue, attempt.newValue
                    ));
                }
            }
        }
        
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeABA()}.
     *
     * @return the findings this detector collected during the run
     */
    public ABAReport analyze() {
        return analyzeABA();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        trackedVariables.clear();
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
    
    public static class ABAReport {
        /** How many A-B-A cycles were observed per variable. */
        public final Map<String, Integer> variablesWithCycles = new HashMap<>();
        /** Compare-and-set calls that succeeded even though the value had changed and changed back. */
        public final Set<String> successfulABACases = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            // A cycle alone is context, not a finding: see the class documentation.
            return !successfulABACases.isEmpty();
        }
        
        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No ABA problems detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.HIGH.format()).append(": ABA PROBLEM DETECTED:\n");
            
            if (!variablesWithCycles.isEmpty()) {
                sb.append("\nVariables with A->B->A cycles (context; a cycle alone is not a finding):\n");
                for (Map.Entry<String, Integer> entry : variablesWithCycles.entrySet()) {
                    sb.append(String.format("  - %s: %d cycles detected%n",
                        entry.getKey(), entry.getValue()));
                }
            }
            
            if (!successfulABACases.isEmpty()) {
                sb.append("\nCAS operations that succeeded despite ABA:\n");
                for (String cas : successfulABACases) {
                    sb.append("  - ").append(cas).append("\n");
                }
                sb.append("""

                          Why: An ABA race occurs when a location holds value A, is changed to B, then changed back to A
                               before a competing CAS reads it. The CAS sees A (as expected) and succeeds — but the underlying
                               object may have been destroyed and recreated, or a linked list node may have been freed and
                               reallocated, leaving the data structure in a corrupt state that the CAS cannot detect.
                          """);
                sb.append("""

                          Fix: Use AtomicStampedReference<V> (pairs value with an integer version stamp) or
                               AtomicMarkableReference<V> (pairs value with a boolean mark) so the CAS compares both
                               the value and the stamp/mark — an A→B→A cycle changes the stamp and the CAS correctly fails
                          """);
            }
            
            sb.append("\nWarning: ABA problems are subtle and can cause:\n");
            sb.append("  - Data structure corruption\n");
            sb.append("  - Lost updates in lock-free structures\n");
            sb.append("  - Incorrect synchronization guarantees\n");
            
            return sb.toString();
        }
    }
}
