package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><strong>What is a finding.</strong> Only that interleaving: a thread records the read its
 * compare-and-set expects ({@link #recordRead(String, Object)}), <em>other</em> threads record a
 * change away from that value and a change back to it, and the first thread records a
 * successful compare-and-set expecting the value it read. Record each event where it happens, on
 * the thread doing it, and record every change: the detector sees records, not operations.
 *
 * <p><strong>How the change back is placed before the compare-and-set.</strong> A change recorded
 * before the compare-and-set's record is taken as before it, and one recorded before the read as
 * seen by it. A change recorded after the compare-and-set is not thereby after it: recording is
 * not atomic with the operation, and another thread can swing A to B to A inside the window and
 * record it late (#779). A timestamp does not settle that. A {@code nanoTime} taken in the record
 * call orders the records, which the record sequence already does, and says nothing about when
 * the operation ran. A {@link HappensBefore} stamp orders two events only where the program
 * published an edge between them, which recording-fed code mostly has not, and in that model an
 * edge only ever removes a finding. The values settle it. A successful compare-and-set leaves the
 * variable at the value it wrote, so a change away from A that came after it needs the variable to
 * leave that value first. When nothing recorded after the read, neither a change nor a successful
 * compare-and-set, takes the variable off the value the compare-and-set wrote, the late A-B-A came
 * before the compare-and-set and it is reported. When something does, the toggle may have followed
 * it, and it is not. The window ends at the next round start ({@link #markInvocationStart()}),
 * because the harness orders its rounds.
 *
 * <p><strong>Limits.</strong> The verdict assumes every change to the variable is recorded. An
 * unrecorded write that takes the variable off the value a compare-and-set wrote hides the one
 * witness that a toggle recorded after it really followed it, so that toggle is reported. The
 * read side has no such witness: a read changes nothing, and it sees A whether an A-B-A ran just
 * before it or just after it. A change recorded after the read is therefore taken as after it
 * (#810). That is wrong only when the toggle ran wholly before the read and both of its records
 * landed after the read's, which needs two threads, one moving the value away and another moving
 * it back, or one thread that makes both changes before recording the first: a thread that
 * records each change right after making it cannot. Those records are the records of a real ABA,
 * so it is reported as one.
 *
 * <p><strong>With the agent.</strong> Attached with {@code fields=true}, the agent substitutes
 * every {@code AtomicReference} {@code get}, {@code getAcquire}, {@code set}, {@code lazySet},
 * {@code setRelease}, {@code compareAndSet} and {@code getAndSet} in woven code, and on a thread
 * whose test has this detector each runs through an {@link AgentSlot}, which takes its record
 * inside the same lock as the operation (#817). So do the reference slots that are not an
 * {@code AtomicReference}: a field reached through an {@code AtomicReferenceFieldUpdater} or a
 * {@code VarHandle}, and an {@code AtomicReferenceArray} element or array element a handle reaches,
 * each a slot of its own. The records of one atomic are then in the order
 * its woven operations ran, so neither limit above applies there: a toggle that ran before a read
 * is recorded before it, and one recorded after a compare-and-set ran after it. The finding is
 * also narrowed to the case an A-B-A can hurt. A compare-and-set whose expected value can carry
 * no state is judged harmless: {@code null}, an enum constant, a boxed number, a {@code String},
 * or a value whose instance fields are all final and reach no such state, followed three fields
 * deep, so a record of numbers is harmless and a record holding a {@code List} is not (#817).
 * Nothing behind a harmless value can have changed while it was away, so a state machine's A-B-A is silent,
 * while a lock-free stack that pushes a popped node back is reported. The agent's records keep no
 * history, only each thread's last read and whether the value left it and came back since, so a
 * hot atomic costs one entry per thread. They are reported under the atomic's class and identity,
 * apart from the named variables above.
 *
 * <p>A value going A to B and back to A is not a finding on its own. One thread pushing and
 * then popping, a flag set and cleared, a counter incremented and decremented: each is an
 * A-B-A history, and none of them hurts a compare-and-set whose premise was read after the
 * toggle, or one taken by the thread that did the toggling. Such cycles are still counted in
 * {@link ABAReport#variablesWithCycles} and shown as context beside a finding.
 */
public class ABAProblemDetector {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    /**
     * Record order across all variables. It orders records, not the operations they describe;
     * see the class documentation for how a change recorded after a compare-and-set is judged.
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * The {@link #sequence} value at each round start, ascending. A change recorded after a round
     * start happened after every compare-and-set recorded before it: the harness orders rounds.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "VO_VOLATILE_REFERENCE_TO_ARRAY",
            justification = "copy-on-write: a published array is never written again, so the "
                    + "volatile reference is the only publication its elements need")
    private volatile long[] roundStarts = new long[0];

    private static class AtomicValueHistory {
        final String varName;
        /**
         * The latest recorded read per thread. A compare-and-set takes its premise from the read
         * just before it, so a later read replaces an earlier one, a compare-and-set consumes
         * the read it was checked against, and a round start drops the rest.
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
        final Map<IdentityKey, CASAttempt> casAttempts = new ConcurrentHashMap<>();
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
        final long threadId;
        /** When a successful attempt was recorded; 0 for a failed one, which moved nothing. */
        final long seq;
        /** The recorded read this attempt was judged against; 0 when it is not judged. */
        final long premiseSeq;
        /** An A-B-A recorded before this attempt; one recorded after it is judged at analysis. */
        final boolean wasABA;

        CASAttempt(Object expected, Object neu, long threadId, long seq, long premiseSeq, boolean wasABA) {
            this.expectedValue = expected;
            this.newValue = neu;
            this.threadId = threadId;
            this.seq = seq;
            this.premiseSeq = premiseSeq;
            this.wasABA = wasABA;
        }
    }
    
    private final Map<String, AtomicValueHistory> trackedVariables = new ConcurrentHashMap<>();

    /**
     * The slots the agent feeds (#817): an {@code AtomicReference} by its identity, and a slot inside
     * a holder (a field reached through an updater or a {@code VarHandle}, or an array element) by
     * a {@link SlotKey}.
     */
    private final Map<Object, AgentSlot> agentSlots = new ConcurrentHashMap<>();

    /**
     * A slot inside {@code holder}: the field {@code selector} (an updater or a handle) reaches, or
     * the element at {@code index} when {@code selector} is {@code null}.
     */
    private record SlotKey(IdentityKey holder, @Nullable IdentityKey selector, int index) { }

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
     * variable in this round saw {@code expectedValue}, and after that read other threads recorded
     * a change away from it and a change back to it (see the class documentation).
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
        
        long tid = Thread.currentThread().threadId();
        // The attempt consumes its premise: a retry reads again before it tries again.
        ValueRead premise = history.reads.remove(tid);

        CASAttempt attempt;
        if (succeeded && premise != null && sameValue(premise.value(), expectedValue)) {
            long seq;
            boolean aba;
            synchronized (history.changesLock) {
                // Sequence taken under the lock, so every change already recorded precedes it.
                seq = sequence.incrementAndGet();
                // Detect ABA: the value moved away and came back while this thread held a stale read
                aba = abaReturn(history.changes, expectedValue, premise.seq(), tid) != 0;
            }
            attempt = new CASAttempt(expectedValue, newValue, tid, seq, premise.seq(), aba);
        } else {
            attempt = new CASAttempt(expectedValue, newValue, tid,
                    succeeded ? sequence.incrementAndGet() : 0, 0, false);
        }

        history.casAttempts.put(new IdentityKey(attempt), attempt);
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
     * The record position of the change back to {@code expected}, when after the attempting
     * thread's read at {@code readSeq} another thread recorded a change away from it and a thread
     * other than the attempting one then recorded a change back; 0 when none did.
     *
     * <p>The attempting thread's own changes do not count: a thread cannot be surprised by a
     * toggle it made itself, and its own compare-and-set is recorded as a change too.
     *
     * <p>Call it holding the history's changes lock: the list is appended to concurrently.
     */
    private static long abaReturn(List<ValueChange> changes, Object expected, long readSeq, long casThread) {
        boolean movedAway = false;
        for (int i = firstAfter(changes, readSeq); i < changes.size(); i++) {
            ValueChange change = changes.get(i);
            if (change.threadId == casThread) {
                continue;
            }
            if (!movedAway) {
                movedAway = sameValue(change.oldValue, expected) && !sameValue(change.newValue, expected);
            } else if (sameValue(change.newValue, expected)) {
                return change.seq; // A -> B -> A behind this thread's back
            }
        }
        return 0;
    }

    /** {@return the index of the first change recorded after {@code seq}; the list is in record order} */
    private static int firstAfter(List<ValueChange> changes, long seq) {
        int low = 0;
        int high = changes.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (changes.get(mid).seq <= seq) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Whether a judged attempt that saw no A-B-A when it was recorded had one after all, recorded
     * late: a change back to its expected value recorded after the attempt, before the next round
     * start, with nothing recorded after its read taking the variable off the value the attempt
     * wrote. The class documentation gives the reasoning.
     *
     * @param successfulBySeq every successful attempt on the variable, by record position
     */
    private boolean abaRecordedLate(AtomicValueHistory history, CASAttempt attempt,
                                    NavigableMap<Long, CASAttempt> successfulBySeq) {
        long windowEnd = windowEnd(attempt.seq);
        // A compare-and-set that expected the value this one wrote took the variable off it.
        for (CASAttempt other : successfulBySeq.subMap(attempt.premiseSeq, false, windowEnd, false).values()) {
            if (sameValue(other.expectedValue, attempt.newValue)) {
                return false;
            }
        }
        synchronized (history.changesLock) {
            List<ValueChange> changes = history.changes;
            int end = firstAfter(changes, windowEnd - 1);
            for (int i = firstAfter(changes, attempt.premiseSeq); i < end; i++) {
                if (sameValue(changes.get(i).oldValue, attempt.newValue)) {
                    return false; // so did this change, whoever made it
                }
            }
            long back = abaReturn(changes.subList(0, end), attempt.expectedValue,
                    attempt.premiseSeq, attempt.threadId);
            return back > attempt.seq;
        }
    }

    /** {@return the record position of the first round start after {@code seq}, or no bound} */
    private long windowEnd(long seq) {
        long[] starts = roundStarts;
        // Positions are unique, so the search never finds seq itself and returns where it would go.
        int insertion = -(Arrays.binarySearch(starts, seq) + 1);
        return insertion < starts.length ? starts[insertion] : Long.MAX_VALUE;
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
            NavigableMap<Long, CASAttempt> successfulBySeq = null;
            for (CASAttempt attempt : history.casAttempts.values()) {
                boolean aba = attempt.wasABA;
                if (!aba && attempt.premiseSeq != 0) {
                    if (successfulBySeq == null) {
                        successfulBySeq = successfulBySeq(history);
                    }
                    aba = abaRecordedLate(history, attempt, successfulBySeq);
                }
                if (aba) {
                    report.successfulABACases.add(String.format(
                        "%s: CAS succeeded despite ABA (expected %s, set to %s)",
                        history.varName, attempt.expectedValue, attempt.newValue
                    ));
                }
            }
        }
        for (AgentSlot slot : agentSlots.values()) {
            slot.reportInto(report);
        }
        
        report.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    private static NavigableMap<Long, CASAttempt> successfulBySeq(AtomicValueHistory history) {
        NavigableMap<Long, CASAttempt> bySeq = new TreeMap<>();
        for (CASAttempt attempt : history.casAttempts.values()) {
            if (attempt.seq != 0) {
                bySeq.put(attempt.seq, attempt);
            }
        }
        return bySeq;
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
        agentSlots.clear();
        roundStarts = new long[0];
    }

    /**
     * Marks the start of a new invocation round. Called by {@code ConcurrencyRunner}, through
     * {@code AsyncTestContext}, after the previous round's workers have all finished, so every
     * change recorded from here on happened after every compare-and-set recorded before it and
     * cannot be the late record of an A-B-A one of them missed.
     *
     * <p>It also drops every read no compare-and-set consumed. A pooled worker runs the body again
     * on the same thread, and its compare-and-set in this round, with no read recorded in it, would
     * otherwise be judged against last round's read and last round's changes, all of which ended
     * before this round began (#810). No worker is running when the runner calls this; a read that
     * a thread the test left running records meanwhile is either kept or dropped, and dropping it
     * only withholds a verdict.
     *
     * @since 1.12.3
     */
    public void markInvocationStart() {
        for (AtomicValueHistory history : trackedVariables.values()) {
            history.reads.clear();
        }
        for (AgentSlot slot : agentSlots.values()) {
            slot.forgetReads();
        }
        long[] started = roundStarts;
        long[] next = Arrays.copyOf(started, started.length + 1);
        next[started.length] = sequence.incrementAndGet();
        roundStarts = next;
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
    
    /**
     * {@return this detector's view of {@code atomic}, through which the agent performs each woven
     * operation on it (#817), or {@code null} while the detector is disabled}
     *
     * <p>Called by the agent's hooks on the thread making the call; test code records by name
     * instead.
     *
     * @param atomic the atomic a woven call site invoked
     * @since 1.12.3
     */
    @API(status = Status.INTERNAL)
    public @Nullable AgentSlot agentSlot(Object atomic) {
        if (!enabled) {
            return null;
        }
        IdentityKey key = new IdentityKey(atomic);
        AgentSlot slot = agentSlots.get(key);
        return slot != null ? slot
                : agentSlots.computeIfAbsent(key, k -> new AgentSlot(atomic.getClass().getSimpleName(), unnamedLabels));
    }

    /**
     * {@return this detector's view of the field {@code selector} reaches inside {@code holder}, or
     * {@code null} while the detector is disabled} (#817)
     *
     * <p>For a reference field reached through an {@code AtomicReferenceFieldUpdater} or a
     * {@code VarHandle}; the agent's hook hands each operation to the view, which performs it
     * under its own lock and records it there.
     *
     * @param holder   the object whose field is the slot
     * @param selector the updater or handle that reaches the field
     * @since 1.12.4
     */
    @API(status = Status.INTERNAL)
    public @Nullable AgentSlot agentSlot(Object holder, Object selector) {
        return enabled ? slotFor(new SlotKey(new IdentityKey(holder), new IdentityKey(selector), 0), holder) : null;
    }

    /**
     * {@return this detector's view of element {@code index} of {@code array}, an
     * {@code AtomicReferenceArray} or an array a {@code VarHandle} reaches, or {@code null} while
     * the detector is disabled} (#817)
     *
     * @param array the array
     * @param index the element's index
     * @since 1.12.4
     */
    @API(status = Status.INTERNAL)
    public @Nullable AgentSlot agentSlot(Object array, int index) {
        return enabled ? slotFor(new SlotKey(new IdentityKey(array), null, index), array) : null;
    }

    private AgentSlot slotFor(SlotKey key, Object holder) {
        AgentSlot slot = agentSlots.get(key);
        return slot != null ? slot
                : agentSlots.computeIfAbsent(key, k -> new AgentSlot(holder.getClass().getSimpleName(), unnamedLabels));
    }

    /**
     * One {@code AtomicReference} the agent feeds (#817): each woven operation on it runs here, with
     * its record taken inside the same lock, so the records of one atomic are in the order its
     * operations ran.
     *
     * <p>Only woven operations of threads whose test has this detector take the lock; any other
     * code touches the atomic as before. A change that bypasses the lock can hide an A-B-A, never
     * make one: every read and change judged here ran in the order it was recorded, so a reported
     * one really went from the value a thread read, away and back, before that thread's
     * compare-and-set expecting it succeeded.
     *
     * <p>No history is kept. Each thread's last read is the premise its next compare-and-set is
     * judged against, as on the recording path, and each change updates the other threads'
     * premises as it happens: away from the value they read, then back to it.
     *
     * @since 1.12.3
     */
    @API(status = Status.INTERNAL)
    public static final class AgentSlot {

        /** How many distinct findings one atomic keeps; each is one line of the report. */
        private static final int MAX_FINDINGS = 16;

        private final String label;
        private final Object lock = new Object();

        /** Guarded by {@link #lock}: one premise per thread that read this atomic. */
        private Premise[] premises = new Premise[2];
        /** Guarded by {@link #lock}. */
        private int premiseCount;
        /** Guarded by {@link #lock}: the last change, for the cycle count shown as context. */
        private @Nullable Object lastOld;
        /** Guarded by {@link #lock}. */
        private @Nullable Object lastNew;
        /** Guarded by {@link #lock}: whether {@link #lastOld} and {@link #lastNew} hold a change. */
        private boolean anyChange;
        /** Guarded by {@link #lock}. */
        private int cycles;
        /** Guarded by {@link #lock}: expected and new value of each A-B-A compare-and-set. */
        private final List<Object[]> findings = new ArrayList<>();

        private AgentSlot(String kind, UnnamedLabels labels) {
            this.label = labels.next(kind);
        }

        /** Reads a reference slot: {@code holder} and {@code selector} or {@code index} name it. */
        @FunctionalInterface
        public interface SlotRead {
            /**
             * @param holder   the object or array holding the slot
             * @param selector the updater or handle that reaches it, or {@code null} for an element
             * @param index    the element's index, or 0
             * @return what the slot holds
             */
            @Nullable Object read(Object holder, @Nullable Object selector, int index);
        }

        /** Stores into a reference slot named as {@link SlotRead} names it. */
        @FunctionalInterface
        public interface SlotStore {
            /**
             * @param holder   the object or array holding the slot
             * @param selector the updater or handle that reaches it, or {@code null} for an element
             * @param index    the element's index, or 0
             * @param value    the reference to store
             */
            void store(Object holder, @Nullable Object selector, int index, @Nullable Object value);
        }

        /** Compares and swaps a reference slot named as {@link SlotRead} names it. */
        @FunctionalInterface
        public interface SlotSwap {
            /**
             * @param holder   the object or array holding the slot
             * @param selector the updater or handle that reaches it, or {@code null} for an element
             * @param index    the element's index, or 0
             * @param expected the reference the slot must hold
             * @param update   the reference to store
             * @return whether it swapped
             */
            boolean swap(Object holder, @Nullable Object selector, int index,
                         @Nullable Object expected, @Nullable Object update);
        }

        /** Stores into a reference slot and returns what it held, named as {@link SlotRead} names it. */
        @FunctionalInterface
        public interface SlotExchange {
            /**
             * @param holder   the object or array holding the slot
             * @param selector the updater or handle that reaches it, or {@code null} for an element
             * @param index    the element's index, or 0
             * @param value    the reference to store
             * @return what the slot held
             */
            @Nullable Object exchange(Object holder, @Nullable Object selector, int index,
                                      @Nullable Object value);
        }

        /**
         * Performs {@code read} on a slot other than an {@code AtomicReference} (#817), recorded as
         * the calling thread's premise, as {@link #get} records.
         *
         * @param holder   the object or array holding the slot
         * @param selector the updater or handle that reaches it, or {@code null} for an element
         * @param index    the element's index, or 0
         * @param read     the read, a non-capturing lambda
         * @return what the read returned
         */
        public @Nullable Object read(Object holder, @Nullable Object selector, int index, SlotRead read) {
            synchronized (lock) {
                Object value = read.read(holder, selector, index);
                read(value);
                return value;
            }
        }

        /**
         * Performs {@code store}, recorded as a change from what {@code current} read before it.
         *
         * @param holder   the object or array holding the slot
         * @param selector the updater or handle that reaches it, or {@code null} for an element
         * @param index    the element's index, or 0
         * @param value    the reference to store
         * @param current  reads what the slot holds now, a non-capturing lambda
         * @param store    the store, a non-capturing lambda
         */
        public void store(Object holder, @Nullable Object selector, int index, @Nullable Object value,
                          SlotRead current, SlotStore store) {
            synchronized (lock) {
                Object old = current.read(holder, selector, index);
                store.store(holder, selector, index, value);
                changed(old, value);
            }
        }

        /**
         * Performs {@code swap}, judged against the calling thread's premise as
         * {@link #compareAndSet} judges it.
         *
         * @param holder   the object or array holding the slot
         * @param selector the updater or handle that reaches it, or {@code null} for an element
         * @param index    the element's index, or 0
         * @param expected the reference the slot must hold
         * @param update   the reference to store
         * @param swap     the compare-and-set, a non-capturing lambda
         * @return whether it swapped
         */
        public boolean compareAndSet(Object holder, @Nullable Object selector, int index,
                                     @Nullable Object expected, @Nullable Object update, SlotSwap swap) {
            // Outside the lock, as in compareAndSet(AtomicReference, ...): it may load classes.
            boolean stateful = canCarryState(expected);
            synchronized (lock) {
                boolean swapped = swap.swap(holder, selector, index, expected, update);
                judgeCompareAndSet(expected, update, swapped, stateful);
                return swapped;
            }
        }

        /**
         * Performs {@code exchange}, recorded as a change from the value it returns.
         *
         * @param holder   the object or array holding the slot
         * @param selector the updater or handle that reaches it, or {@code null} for an element
         * @param index    the element's index, or 0
         * @param value    the reference to store
         * @param exchange the get-and-set, a non-capturing lambda
         * @return what the slot held
         */
        public @Nullable Object getAndSet(Object holder, @Nullable Object selector, int index,
                                          @Nullable Object value, SlotExchange exchange) {
            synchronized (lock) {
                Object old = exchange.exchange(holder, selector, index, value);
                changed(old, value);
                return old;
            }
        }

        /** Judges a compare-and-set against the caller's premise and records it. Call holding {@link #lock}. */
        @SuppressWarnings("ReferenceEquality") // the slot compares identity, so this does too
        private void judgeCompareAndSet(@Nullable Object expected, @Nullable Object update,
                                        boolean swapped, boolean stateful) {
            Premise mine = premiseOf(Thread.currentThread().threadId());
            if (mine != null && mine.live) {
                if (swapped && mine.cameBack && mine.value == expected // NOPMD CompareObjectsWithEquals - identity, as the slot compares
                        && stateful && findings.size() < MAX_FINDINGS) {
                    findings.add(new Object[] {expected, update});
                }
                mine.live = false;
            }
            if (swapped) {
                changed(expected, update);
            }
        }

        /** A thread's last read of the atomic and what happened to that value since. */
        private static final class Premise {
            final long thread;
            @Nullable Object value;
            boolean live;
            boolean movedAway;
            boolean cameBack;

            Premise(long thread) {
                this.thread = thread;
            }
        }

        /**
         * Performs {@code AtomicReference.get}, recorded as the calling thread's premise.
         *
         * @param atomic the atomic this slot describes
         * @return what {@code get} returned
         */
        public @Nullable Object get(AtomicReference<Object> atomic) {
            synchronized (lock) {
                Object value = atomic.get();
                read(value);
                return value;
            }
        }

        /**
         * Performs {@code AtomicReference.getAcquire}, recorded as {@link #get} records.
         *
         * @param atomic the atomic this slot describes
         * @return what {@code getAcquire} returned
         */
        public @Nullable Object getAcquire(AtomicReference<Object> atomic) {
            synchronized (lock) {
                Object value = atomic.getAcquire();
                read(value);
                return value;
            }
        }

        /**
         * Performs {@code AtomicReference.set}, recorded as a change from what the atomic held.
         *
         * @param atomic the atomic this slot describes
         * @param value  the reference to store
         */
        public void set(AtomicReference<Object> atomic, @Nullable Object value) {
            synchronized (lock) {
                Object old = atomic.get();
                atomic.set(value);
                changed(old, value);
            }
        }

        /**
         * Performs {@code AtomicReference.lazySet}, recorded as {@link #set} records.
         *
         * @param atomic the atomic this slot describes
         * @param value  the reference to store
         */
        public void lazySet(AtomicReference<Object> atomic, @Nullable Object value) {
            synchronized (lock) {
                Object old = atomic.get();
                atomic.lazySet(value);
                changed(old, value);
            }
        }

        /**
         * Performs {@code AtomicReference.setRelease}, recorded as {@link #set} records.
         *
         * @param atomic the atomic this slot describes
         * @param value  the reference to store
         */
        public void setRelease(AtomicReference<Object> atomic, @Nullable Object value) {
            synchronized (lock) {
                Object old = atomic.get();
                atomic.setRelease(value);
                changed(old, value);
            }
        }

        /**
         * Performs {@code AtomicReference.getAndSet}, recorded as a change from the value it
         * returns.
         *
         * @param atomic the atomic this slot describes
         * @param value  the reference to store
         * @return the reference the atomic held
         */
        public @Nullable Object getAndSet(AtomicReference<Object> atomic, @Nullable Object value) {
            synchronized (lock) {
                Object old = atomic.getAndSet(value);
                changed(old, value);
                return old;
            }
        }

        /**
         * Performs {@code AtomicReference.compareAndSet}, judged against the calling thread's
         * premise and recorded, when it swapped, as a change from {@code expected}. Either way the
         * premise is used up: a retry reads again.
         *
         * @param atomic   the atomic this slot describes
         * @param expected the reference the atomic must hold
         * @param update   the reference to store
         * @return whether it swapped
         */
        @SuppressWarnings("ReferenceEquality") // the atomic compares identity, so this does too
        public boolean compareAndSet(AtomicReference<Object> atomic, @Nullable Object expected,
                                     @Nullable Object update) {
            // Outside the lock: the first answer for a class reads its fields, which may load
            // classes, and nothing that can load a class runs under this lock.
            boolean stateful = canCarryState(expected);
            synchronized (lock) {
                boolean swapped = atomic.compareAndSet(expected, update);
                judgeCompareAndSet(expected, update, swapped, stateful);
                return swapped;
            }
        }

        /** Records the calling thread's read. Call holding {@link #lock}. */
        private void read(@Nullable Object value) {
            long me = Thread.currentThread().threadId();
            Premise mine = premiseOf(me);
            if (mine == null) {
                if (premiseCount == premises.length) {
                    premises = Arrays.copyOf(premises, premiseCount * 2);
                }
                mine = new Premise(me);
                premises[premiseCount] = mine;
                premiseCount++;
            }
            mine.value = value;
            mine.live = true;
            mine.movedAway = false;
            mine.cameBack = false;
        }

        /**
         * Applies a change the calling thread made to every other thread's premise, and counts a
         * change that undoes the one before it. Call holding {@link #lock}.
         */
        @SuppressWarnings("ReferenceEquality") // the atomic compares identity, so this does too
        private void changed(@Nullable Object old, @Nullable Object neu) {
            long me = Thread.currentThread().threadId();
            for (int i = 0; i < premiseCount; i++) {
                Premise other = premises[i];
                if (other.thread == me || !other.live || other.cameBack) {
                    continue;
                }
                if (!other.movedAway) {
                    other.movedAway = old == other.value && neu != other.value; // NOPMD CompareObjectsWithEquals - identity
                } else if (neu == other.value) { // NOPMD CompareObjectsWithEquals - identity
                    other.cameBack = true;
                }
            }
            if (anyChange && old == lastNew && neu == lastOld && lastOld != lastNew) { // NOPMD CompareObjectsWithEquals - identity
                cycles++;
            }
            lastOld = old;
            lastNew = neu;
            anyChange = true;
        }

        /** {@return the premise of {@code thread}, or {@code null}}. Call holding {@link #lock}. */
        private @Nullable Premise premiseOf(long thread) {
            for (int i = 0; i < premiseCount; i++) {
                if (premises[i].thread == thread) {
                    return premises[i];
                }
            }
            return null;
        }

        /** Drops every premise: the harness orders every read before a round start (#810). */
        private void forgetReads() {
            synchronized (lock) {
                for (int i = 0; i < premiseCount; i++) {
                    premises[i].live = false;
                }
            }
        }

        /** Adds this atomic's findings and cycle count to {@code report}. */
        private void reportInto(ABAReport report) {
            List<Object[]> found;
            int cycleCount;
            synchronized (lock) {
                found = new ArrayList<>(findings);
                cycleCount = cycles;
            }
            // Formatted outside the lock: toString is the program's code.
            if (cycleCount > 0) {
                report.variablesWithCycles.put(label, cycleCount);
            }
            for (Object[] finding : found) {
                report.successfulABACases.add(String.format(
                        "%s: CAS succeeded despite ABA (expected %s, set to %s)",
                        label, finding[0], finding[1]));
            }
        }
    }

    /** What a class's instances are, for {@link #canCarryState}. */
    private enum Shape {
        /** A JDK value or an enum constant: an A-B-A of it leaves nothing stale. */
        VALUE,
        /** It has a mutable field of its own, or is an array, or its fields cannot be read. */
        MUTABLE,
        /** Every instance field is final; whether it carries state is a question for its values. */
        FINAL
    }

    /**
     * Each class's {@link Shape} (#817). The JDK's value classes are listed rather than read: a
     * {@code String} or a {@code BigInteger} caches a hash or a magnitude in a non-final field
     * without being any less a value.
     */
    private static final ClassValue<Shape> SHAPE = new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> type) {
            if (type.isArray()) {
                return Shape.MUTABLE;
            }
            if (type.isEnum() || type == String.class || type == Boolean.class || type == Character.class
                    || Number.class.isAssignableFrom(type) && type.getName().startsWith("java.")) {
                return Shape.VALUE;
            }
            try {
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        int modifiers = field.getModifiers();
                        if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                            return Shape.MUTABLE;
                        }
                    }
                }
            } catch (LinkageError | SecurityException unreadable) {
                return Shape.MUTABLE; // its fields cannot be read, so it may have such a field
            }
            return Shape.FINAL;
        }
    };

    /** A class's reference fields opened for reading; {@code readable} is false when one could not be. */
    private record References(Field[] fields, boolean readable) {
        static final References UNREADABLE = new References(new Field[0], false);
    }

    /**
     * The reference fields of each {@link Shape#FINAL} class, opened for reading; a class with one
     * that cannot be opened counts as carrying state.
     */
    private static final ClassValue<References> FINAL_REFERENCES = new ClassValue<>() {
        @Override
        protected References computeValue(Class<?> type) {
            List<Field> references = new ArrayList<>();
            try {
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                            continue;
                        }
                        if (!field.trySetAccessible()) {
                            return References.UNREADABLE;
                        }
                        references.add(field);
                    }
                }
            } catch (LinkageError | RuntimeException unreadable) {
                return References.UNREADABLE;
            }
            return new References(references.toArray(new Field[0]), true);
        }
    };

    /** How many final fields deep {@link #canCarryState} follows a value; past it, a value is harmless. */
    private static final int REACH = 3;

    /**
     * {@return whether an A-B-A of {@code value} can leave state behind it stale}
     *
     * <p>A value whose instance fields are all final carries state when one of them reaches an
     * object that does, a record holding a {@code List} for one, followed {@value #REACH} fields
     * deep (#817). Reading those fields may load classes the first time a class is met, so this is
     * decided before a slot's lock is taken.
     *
     * @param value the expected value of a compare-and-set
     * @since 1.12.4
     */
    @API(status = Status.INTERNAL)
    public static boolean canCarryState(@Nullable Object value) {
        return carriesState(value, 0);
    }

    private static boolean carriesState(@Nullable Object value, int depth) {
        if (value == null) {
            return false;
        }
        Shape shape = SHAPE.get(value.getClass());
        if (shape != Shape.FINAL) {
            return shape == Shape.MUTABLE;
        }
        if (depth >= REACH) {
            return false;
        }
        References references = FINAL_REFERENCES.get(value.getClass());
        if (!references.readable()) {
            return true; // a field that cannot be read may reach anything
        }
        for (Field field : references.fields()) {
            try {
                if (carriesState(field.get(value), depth + 1)) {
                    return true;
                }
            } catch (IllegalAccessException | RuntimeException unreadable) {
                return true;
            }
        }
        return false;
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
        
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding, worded as its text line (#801); called once before the report is returned. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
            for (String cas : successfulABACases) {
                structuredViolations.add(new Violation("ABAProblem", severity,
                        cas, List.of(), Map.of(), Instant.now()));
            }
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
