package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects non-atomic read-modify-write sequences through a {@code VarHandle} — a {@code get}
 * followed by a {@code set} where {@code compareAndExchange} was needed — and plain-mode access
 * to a location several threads share.
 *
 * <p>This is {@link AtomicNonAtomicUpdateDetector}'s rule for the {@code VarHandle} ecosystem
 * (JDK 9+), and the bug is the same one: a {@code get} then {@code set} pair is two atomic
 * operations, not one atomic compound operation, so a write landing between them is silently
 * overwritten. The access mode does not rescue it. {@code getVolatile} followed by
 * {@code setVolatile} loses updates exactly as readily as the plain pair, because volatile buys
 * ordering and visibility, never atomicity across two separate operations. Only the CAS family
 * — {@code compareAndSet}, {@code compareAndExchange}, {@code getAndAdd}, {@code getAndSet} —
 * makes the read and the write indivisible.
 *
 * <p>A second, independent rule covers the mistake that is unique to {@code VarHandle}: the
 * <em>plain</em> {@code get}/{@code set} access modes carry no memory-model guarantee at all,
 * even when the underlying field is declared {@code volatile}. Code that reaches for a
 * {@code VarHandle} to get atomics without boxing frequently reaches for {@code vh.get(o)} out
 * of habit and loses the ordering it thought the {@code volatile} keyword gave it.
 *
 * <p><strong>Trust tier.</strong> The lost-update finding is a verdict, not a prompt: a
 * get-then-set on the same location by the same thread with no intervening atomic operation is
 * wrong under concurrency no matter how the surrounding code is synchronized — if a lock did
 * make it safe, the {@code VarHandle} is doing nothing the field access could not. The
 * plain-mode finding is a prompt at MEDIUM: external synchronization can supply the missing
 * ordering, and the report says so.
 *
 * <p>The {@code varHandle} parameter is typed {@link Object} to match the rest of the detector
 * set, which keeps recording calls uniform and avoids a hard dependency on the handle's identity
 * semantics.
 *
 * <p>Usage:
 * <pre>{@code
 * private static final VarHandle COUNT =
 *     MethodHandles.lookup().findVarHandle(Holder.class, "count", int.class);
 *
 * var d = AsyncTestContext.varHandleNonAtomicUpdateDetector();
 * int v = (int) COUNT.getVolatile(holder);
 * d.recordGet(COUNT, holder, "count", Mode.VOLATILE, Thread.currentThread());
 * COUNT.setVolatile(holder, v + 1);                       // BUG: the read-modify-write is not atomic
 * d.recordSet(COUNT, holder, "count", Mode.VOLATILE, Thread.currentThread());
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-location state in ConcurrentHashMap keyed on a (handle, receiver) identity "
        + "record; pending reads are a per-thread ConcurrentHashMap entry; counters are "
        + "LongAdder and detail lists are CopyOnWriteArrayList bounded by MAX_DETAILS.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/VarHandleNonAtomicUpdateDetectorTest.java"
)
public final class VarHandleNonAtomicUpdateDetector {

    /** Cap on retained per-location detail lines, so a hot loop cannot grow the report without bound. */
    static final int MAX_DETAILS = 20;

    /**
     * The {@code VarHandle} access mode used for a recorded operation. Only
     * {@link #PLAIN} lacks ordering; none of them makes a get-then-set pair atomic.
     */
    public enum Mode {
        /** {@code get}/{@code set} — no ordering, no visibility guarantee, even on a volatile field. */
        PLAIN,
        /** {@code getOpaque}/{@code setOpaque} — progress and coherence, but no happens-before edge. */
        OPAQUE,
        /** {@code getAcquire}/{@code setRelease} — one-directional ordering. */
        ACQUIRE_RELEASE,
        /** {@code getVolatile}/{@code setVolatile} — full ordering, still not atomic across two calls. */
        VOLATILE
    }

    private record Location(int handleId, int receiverId) { }

    private static final class State {
        final String label;
        final Map<Long, String> pendingReadByThread = new ConcurrentHashMap<>();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final Set<Long>   threadIds   = ConcurrentHashMap.newKeySet();
        final LongAdder lostUpdates = new LongAdder();
        final List<String> details  = new CopyOnWriteArrayList<>();
        final AtomicBoolean sawPlainAccess    = new AtomicBoolean();
        final AtomicBoolean sawOrderedAccess  = new AtomicBoolean();
        final AtomicBoolean sawPlainWrite     = new AtomicBoolean();
        State(String label) { this.label = label; }
    }

    private final Map<Location, State> locations = new ConcurrentHashMap<>();

    /**
     * Record a read through a {@code VarHandle}. Opens a pending read-modify-write for the
     * calling thread; a later {@link #recordSet} with no intervening
     * {@link #recordAtomicUpdate} closes it as a lost update.
     *
     * @param varHandle the {@code VarHandle}, typed {@link Object} (null-safe)
     * @param receiver  the object whose field is being read, or {@code null} for a static field
     * @param label     human-readable label for the location (may be {@code null})
     * @param mode      the access mode used
     * @param thread    the reading thread
     */
    public void recordGet(@Nullable Object varHandle, @Nullable Object receiver,
                          @Nullable String label, @Nullable Mode mode, @Nullable Thread thread) {
        State s = stateFor(varHandle, receiver, label);
        if (s == null || thread == null) return;
        note(s, mode, thread, false);
        s.pendingReadByThread.put(thread.threadId(), mode == null ? "PLAIN" : mode.name());
    }

    /**
     * Record a write through a {@code VarHandle}. If the same thread has an open pending read on
     * this location, the pair is a non-atomic read-modify-write and is counted as a lost update.
     *
     * @param varHandle the {@code VarHandle}, typed {@link Object} (null-safe)
     * @param receiver  the object whose field is being written, or {@code null} for a static field
     * @param label     human-readable label for the location (may be {@code null})
     * @param mode      the access mode used
     * @param thread    the writing thread
     */
    public void recordSet(@Nullable Object varHandle, @Nullable Object receiver,
                          @Nullable String label, @Nullable Mode mode, @Nullable Thread thread) {
        State s = stateFor(varHandle, receiver, label);
        if (s == null || thread == null) return;
        note(s, mode, thread, true);
        String readMode = s.pendingReadByThread.remove(thread.threadId());
        if (readMode != null) {
            s.lostUpdates.increment();
            if (s.details.size() < MAX_DETAILS) {
                s.details.add(String.format(
                    "thread '%s' read '%s' with %s then wrote it with %s, no compareAndSet between",
                    thread.getName(), s.label, readMode, mode == null ? "PLAIN" : mode.name()));
            }
        }
    }

    /**
     * Record an atomic update through a {@code VarHandle} — {@code compareAndSet},
     * {@code compareAndExchange}, {@code getAndAdd}, {@code getAndSet} or a sibling. Clears the
     * calling thread's pending read, because the read and the write were indivisible.
     *
     * @param varHandle the {@code VarHandle}, typed {@link Object} (null-safe)
     * @param receiver  the object whose field is being updated, or {@code null} for a static field
     * @param label     human-readable label for the location (may be {@code null})
     * @param thread    the updating thread
     */
    public void recordAtomicUpdate(@Nullable Object varHandle, @Nullable Object receiver,
                                   @Nullable String label, @Nullable Thread thread) {
        State s = stateFor(varHandle, receiver, label);
        if (s == null || thread == null) return;
        note(s, Mode.VOLATILE, thread, true);
        s.pendingReadByThread.remove(thread.threadId());
    }

    private static void note(State s, @Nullable Mode mode, Thread thread, boolean write) {
        s.threadIds.add(thread.threadId());
        s.threadNames.add(thread.getName());
        if (mode == null || mode == Mode.PLAIN) {
            s.sawPlainAccess.set(true);
            if (write) s.sawPlainWrite.set(true);
        } else {
            s.sawOrderedAccess.set(true);
        }
    }

    private @Nullable State stateFor(@Nullable Object varHandle, @Nullable Object receiver,
                                     @Nullable String label) {
        if (varHandle == null) return null;
        Location key = new Location(System.identityHashCode(varHandle),
                                    receiver == null ? 0 : System.identityHashCode(receiver));
        State s = locations.get(key);
        if (s == null) {
            final String lbl = label != null ? label : "VarHandle@" + key.handleId();
            s = locations.computeIfAbsent(key, k -> new State(lbl));
        }
        return s;
    }

    /**
     * Evaluate the observed state and produce a report. Idempotent: calling it N times on
     * quiescent state yields N identical reports.
     *
     * @return the report of lost updates and unordered plain-mode sharing
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : locations.values()) {
            long lost = s.lostUpdates.sum();
            if (lost > 0) {
                StringBuilder msg = new StringBuilder(String.format(
                    "HIGH: '%s' was updated by %d non-atomic get-then-set sequence(s) through a "
                    + "VarHandle. A read and a write are two operations: a concurrent write "
                    + "landing between them is overwritten and lost. The access mode does not "
                    + "help — setVolatile still publishes a value computed from a stale read.",
                    s.label, lost));
                for (String d : s.details) msg.append("\n      * ").append(d);
                add(r, s, IssueSeverity.HIGH, msg.toString());
            }

            if (s.threadIds.size() > 1 && s.sawPlainAccess.get() && s.sawPlainWrite.get()) {
                add(r, s, IssueSeverity.MEDIUM, String.format(
                    "MEDIUM: '%s' was accessed by %d threads (%s) using the plain VarHandle access "
                    + "mode, including at least one write.%s Plain get/set carries no ordering or "
                    + "visibility guarantee even when the underlying field is declared volatile, "
                    + "so a reader may never observe the write. Verify the accesses are ordered "
                    + "by something the detector cannot see before dismissing this.",
                    s.label, s.threadIds.size(), String.join(", ", s.threadNames),
                    s.sawOrderedAccess.get()
                        ? " Some accesses to the same location did use an ordered mode, which is"
                          + " the mixed-mode case: the guarantee is only as strong as the weakest"
                          + " access."
                        : ""));
            }
        }
        return r;
    }

    private static void add(Report r, State s, IssueSeverity severity, String msg) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "VarHandleNonAtomicUpdate",
                severity,
                msg,
                List.of(),
                Map.of("label", s.label, "threadCount", s.threadIds.size()),
                Instant.now()));
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report {
        /** Human-readable findings, one per violation. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as machine-readable {@link Violation} records. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * Checks if any issues were detected.
         *
         * @return true if there are violations, false otherwise
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "VarHandleNonAtomicUpdate — clean";
            StringBuilder sb = new StringBuilder("VARHANDLE NON-ATOMIC UPDATE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Replace the get/set pair with a CAS retry loop:\n")
              .append("        int old; do { old = (int) VH.getVolatile(o); } ")
              .append("while ((int) VH.compareAndExchange(o, old, f(old)) != old);\n")
              .append("    - For counters and accumulators use VH.getAndAdd(o, delta), or ")
              .append("LongAdder when the contention is high enough that CAS retries dominate.\n")
              .append("    - Never use the plain get/set modes on a location another thread ")
              .append("touches: they are the one VarHandle mode with no ordering, and declaring ")
              .append("the field volatile does not change what the plain mode does.\n")
              .append("    - If the whole update needs a lock anyway, use the lock and a normal ")
              .append("field; a VarHandle inside a synchronized block buys nothing.\n");
            return sb.toString();
        }
    }
}
