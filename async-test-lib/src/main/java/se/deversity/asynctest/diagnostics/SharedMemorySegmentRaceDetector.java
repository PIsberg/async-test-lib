package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects unsynchronized concurrent access to overlapping regions of a shared
 * {@code MemorySegment} (FFM API, JEP 454, final in JDK 22), and access to a segment after its
 * arena has been closed.
 *
 * <p>{@code Arena.ofShared()} lifts the confinement check that
 * {@link ConfinedArenaThreadEscapeDetector} watches, and lifting it is the whole problem: a
 * shared segment can be touched from any thread, but plain {@code get}/{@code set} on a segment
 * carries <em>no</em> memory-model guarantee. Two threads writing overlapping bytes race exactly
 * like two threads writing a plain field, with no volatile semantics and no tearing guarantee
 * above 8 bytes. The FFM API's answer is a {@code VarHandle} with an atomic access mode, or
 * ordinary mutual exclusion.
 *
 * <p><strong>Trust tier.</strong> Byte-range overlap alone cannot tell a race from correctly
 * locked sharing, which is the same limit that makes the older access-pattern detectors report
 * "verify synchronization" rather than "this is broken". This detector narrows that gap with an
 * optional lock model: pass a {@code guard} label to {@link #recordAccess(Object, String, long,
 * long, boolean, Thread, String)} naming the monitor held during the access, and overlapping
 * accesses that agree on a non-null guard are treated as synchronized and stay silent. Findings
 * therefore split into two tiers, and the wording says which one you are reading:
 * <ul>
 *   <li>overlap with <em>conflicting</em> guards, or a mix of guarded and unguarded access —
 *       a genuine defect, reported at HIGH;</li>
 *   <li>overlap with no guard recorded anywhere — a prompt to verify, reported at MEDIUM,
 *       because the test may simply not have told the detector about its locks.</li>
 * </ul>
 * Use-after-close is neither: it is unconditionally CRITICAL.
 *
 * <p>The parameter types are {@link Object} rather than {@code java.lang.foreign.MemorySegment}
 * so this class compiles on the library's JDK 21 baseline, where the FFM API is still preview.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = AsyncTestContext.sharedMemorySegmentRaceDetector();
 * try (Arena arena = Arena.ofShared()) {
 *     MemorySegment seg = arena.allocate(4096);
 *     // ... from several threads ...
 *     seg.set(JAVA_INT, 0, value);
 *     d.recordAccess(seg, "ringBuffer", 0, 4, true, Thread.currentThread());  // unguarded write
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-segment state in ConcurrentHashMap; the access log is a CopyOnWriteArrayList "
        + "bounded by MAX_TRACKED_ACCESSES with a LongAdder drop counter, so an unbounded test "
        + "cannot exhaust the heap and the report states how many samples were dropped.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedMemorySegmentRaceDetectorTest.java"
)
public final class SharedMemorySegmentRaceDetector {

    /**
     * Per-segment cap on retained access records. Beyond this the detector counts drops instead
     * of growing: a stress test doing a million writes must not turn the detector into the leak.
     * Overlap between the first {@value} accesses is enough to find the bug; the drop count is
     * reported so a clean result is never mistaken for full coverage.
     */
    static final int MAX_TRACKED_ACCESSES = 1024;

    /** How many distinct overlapping pairs to name per segment before summarising. */
    private static final int MAX_REPORTED_PAIRS = 3;

    private record Access(long threadId, String threadName, long start, long end,
                          boolean write, @Nullable String guard) { }

    private static final class SegmentState {
        final String label;
        final List<Access> accesses = new CopyOnWriteArrayList<>();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final LongAdder dropped = new LongAdder();
        final LongAdder afterClose = new LongAdder();
        final Set<String> afterCloseThreads = ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();
        SegmentState(String label) { this.label = label; }
    }

    private final Map<Integer, SegmentState> segments = new ConcurrentHashMap<>();

    /**
     * Record an access to a byte range of a shared segment, with no lock information.
     * Equivalent to calling the seven-argument overload with a {@code null} guard.
     *
     * @param segment the {@code MemorySegment} accessed, typed {@link Object} (null-safe)
     * @param label   human-readable label for triage (may be {@code null})
     * @param offset  byte offset of the access
     * @param length  byte length of the access
     * @param write   {@code true} for a write, {@code false} for a read
     * @param thread  the accessing thread
     */
    public void recordAccess(@Nullable Object segment, @Nullable String label,
                             long offset, long length, boolean write, @Nullable Thread thread) {
        recordAccess(segment, label, offset, length, write, thread, null);
    }

    /**
     * Record an access to a byte range of a shared segment, naming the lock held for its
     * duration. Overlapping accesses that agree on a non-null guard are treated as correctly
     * synchronized and produce no finding, which is what lets this detector distinguish a race
     * from safe sharing instead of flagging both.
     *
     * @param segment the {@code MemorySegment} accessed, typed {@link Object} (null-safe)
     * @param label   human-readable label for triage (may be {@code null})
     * @param offset  byte offset of the access
     * @param length  byte length of the access
     * @param write   {@code true} for a write, {@code false} for a read
     * @param thread  the accessing thread
     * @param guard   label of the monitor held during the access, or {@code null} if unguarded
     */
    public void recordAccess(@Nullable Object segment, @Nullable String label,
                             long offset, long length, boolean write,
                             @Nullable Thread thread, @Nullable String guard) {
        if (segment == null || thread == null || length <= 0) return;
        SegmentState s = stateFor(segment, label);
        s.threadNames.add(thread.getName());

        if (s.closed.get()) {
            s.afterClose.increment();
            s.afterCloseThreads.add(thread.getName());
        }

        if (s.accesses.size() >= MAX_TRACKED_ACCESSES) {
            s.dropped.increment();
            return;
        }
        s.accesses.add(new Access(thread.threadId(), thread.getName(),
                                  offset, offset + length, write, guard));
    }

    /**
     * Record that the segment's arena has been closed. Every access recorded after this point is
     * a use-after-free against freed native memory.
     *
     * @param segment the segment whose arena closed, typed {@link Object} (null-safe)
     * @param label   human-readable label for triage (may be {@code null})
     */
    public void recordClose(@Nullable Object segment, @Nullable String label) {
        if (segment == null) return;
        stateFor(segment, label).closed.set(true);
    }

    private SegmentState stateFor(Object segment, @Nullable String label) {
        int id = System.identityHashCode(segment);
        SegmentState s = segments.get(id);
        if (s == null) {
            final String lbl = label != null ? label : "MemorySegment@" + id;
            s = segments.computeIfAbsent(id, k -> new SegmentState(lbl));
        }
        return s;
    }

    /**
     * Evaluate the observed state and produce a report. Idempotent: calling it N times on
     * quiescent state yields N identical reports.
     *
     * @return the report of overlapping unsynchronized accesses and use-after-close accesses
     */
    public Report analyze() {
        Report r = new Report();
        for (SegmentState s : segments.values()) {
            long afterClose = s.afterClose.sum();
            if (afterClose > 0) {
                add(r, s, IssueSeverity.CRITICAL, String.format(
                    "CRITICAL: segment '%s' was accessed %d time(s) by thread(s) %s after its "
                    + "arena was closed. Closing an arena frees the backing memory, so this reads "
                    + "or writes memory the JVM has already released.",
                    s.label, afterClose, String.join(", ", s.afterCloseThreads)));
            }

            List<String> conflicting = new ArrayList<>();
            List<String> unguarded   = new ArrayList<>();
            findOverlaps(s, conflicting, unguarded);

            if (!conflicting.isEmpty()) {
                add(r, s, IssueSeverity.HIGH, String.format(
                    "HIGH: segment '%s' has %d overlapping concurrent access(es) whose locking "
                    + "disagrees — %s. Threads that guard the same bytes with different monitors, "
                    + "or where one guards and another does not, are not mutually excluded: this "
                    + "is a data race on native memory, and plain segment access carries no "
                    + "memory-model guarantee that would rescue it.",
                    s.label, conflicting.size(), summarise(conflicting)));
            }
            if (!unguarded.isEmpty()) {
                add(r, s, IssueSeverity.MEDIUM, String.format(
                    "MEDIUM: segment '%s' has %d overlapping concurrent access(es) with no lock "
                    + "recorded on either side — %s. Plain MemorySegment access has no ordering "
                    + "or atomicity guarantee, so this is a race unless the accesses are ordered "
                    + "outside the test's view; pass a guard label to recordAccess to let the "
                    + "detector rule it out.",
                    s.label, unguarded.size(), summarise(unguarded)));
            }

            long dropped = s.dropped.sum();
            if (dropped > 0 && (!conflicting.isEmpty() || !unguarded.isEmpty())) {
                r.violations.add(String.format(
                    "NOTE: segment '%s' exceeded the %d-access tracking cap; %d further access(es) "
                    + "were not compared. The findings above are a lower bound.",
                    s.label, MAX_TRACKED_ACCESSES, dropped));
            }
        }
        return r;
    }

    /**
     * Sweep the access log for overlapping byte ranges touched by two different threads where at
     * least one is a write, splitting the hits by whether the recorded locking explains them.
     */
    private static void findOverlaps(SegmentState s, List<String> conflicting, List<String> unguarded) {
        List<Access> sorted = new ArrayList<>(s.accesses);
        sorted.sort(Comparator.comparingLong(Access::start));
        Set<String> seenPairs = new LinkedHashSet<>();

        for (int i = 0; i < sorted.size(); i++) {
            Access a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                Access b = sorted.get(j);
                if (b.start() >= a.end()) break;              // sorted by start: nothing later overlaps
                if (a.threadId() == b.threadId()) continue;   // same thread cannot race itself
                if (!a.write() && !b.write()) continue;       // read/read is always safe

                boolean bothGuarded = a.guard() != null && b.guard() != null;
                boolean sameGuard   = bothGuarded && Objects.equals(a.guard(), b.guard());
                if (sameGuard) continue;                      // mutual exclusion explains it

                long from = Math.max(a.start(), b.start());
                long to   = Math.min(a.end(), b.end());
                String pair = String.format("bytes [%d,%d) between '%s' and '%s'",
                        from, to, a.threadName(), b.threadName());
                if (!seenPairs.add(pair)) continue;

                if (a.guard() != null || b.guard() != null) {
                    conflicting.add(pair + String.format(" (guards: %s vs %s)",
                            a.guard() == null ? "none" : a.guard(),
                            b.guard() == null ? "none" : b.guard()));
                } else {
                    unguarded.add(pair);
                }
            }
        }
    }

    private static String summarise(List<String> pairs) {
        if (pairs.size() <= MAX_REPORTED_PAIRS) return String.join("; ", pairs);
        return String.join("; ", pairs.subList(0, MAX_REPORTED_PAIRS))
             + String.format("; and %d more", pairs.size() - MAX_REPORTED_PAIRS);
    }

    private static void add(Report r, SegmentState s, IssueSeverity severity, String msg) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "SharedMemorySegmentRace",
                severity,
                msg,
                List.of(),
                Map.of("label", s.label, "threadCount", s.threadNames.size()),
                Instant.now()));
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report implements GradedFindings {
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

        /**
         * One grade per finding, so a verdict-grade finding is not held back by a weaker one from
         * the same detector.
         *
         * <p>Access after the arena closed is a verdict: the segment's lifetime ended, and using it is
         * undefined behaviour whatever else the program does. The overlapping-access findings depend
         * on locks the detector was told about, so they stay prompts, exactly as they do for every
         * other detector without a complete lock model.
         */
        @Override
        public List<GradedFindings.Grade> grades() {
            return structuredViolations.stream()
                    .map(v -> new GradedFindings.Grade(v.severity(), tierOf(v.severity()), v.message()))
                    .toList();
        }

        private static TrustTier tierOf(IssueSeverity severity) {
            return switch (severity) {
            case CRITICAL -> TrustTier.VERDICT;
            default -> TrustTier.PROMPT;
            };
        }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SharedMemorySegmentRace — clean";
            StringBuilder sb = new StringBuilder("SHARED MEMORY SEGMENT RACE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use a VarHandle from MemoryLayout.varHandle(...) with an atomic ")
              .append("access mode (compareAndSet, getAndAdd, getVolatile/setVolatile) for any ")
              .append("location two threads touch; plain segment get/set has no ordering.\n")
              .append("    - Or partition the segment so each thread owns a disjoint byte range ")
              .append("via asSlice(), which removes the sharing instead of guarding it.\n")
              .append("    - Or hold one agreed monitor across every access to the region, and ")
              .append("record it as the guard so this detector can confirm the exclusion.\n")
              .append("    - Close the arena only after every reader has finished; a shared ")
              .append("arena's close() is visible to all threads immediately.\n");
            return sb.toString();
        }
    }
}
