package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detects memory segments allocated from a confined {@code Arena} (FFM API, JEP 454, final
 * in JDK 22) escaping to a thread that is not the arena's owner, and access to segments whose
 * arena has already been closed.
 *
 * <p>{@code Arena.ofConfined()} binds every segment it allocates to the creating thread. Any
 * access from another thread throws {@code WrongThreadException}, and {@code close()} from a
 * non-owner throws the same. This is not a synchronization problem that a lock can fix: the
 * confinement check is a hard JVM rule, so a finding here is a defect regardless of how the
 * surrounding code is synchronized. Sharing across threads requires {@code Arena.ofShared()},
 * whose segments carry their own race exposure — see
 * {@link SharedMemorySegmentRaceDetector}.
 *
 * <p><strong>Why the findings are trustworthy.</strong> Confinement is not inferred from the
 * observed thread set. The detector asks the JVM directly, calling
 * {@code MemorySegment.isAccessibleBy(Thread)} reflectively against a never-started probe
 * thread: a segment that rejects the probe is confined, and one that rejects the recording
 * thread is being touched from the wrong thread. Liveness comes from
 * {@code MemorySegment.scope().isAlive()} the same way. When those methods are unavailable —
 * a JDK 21 baseline without the final FFM API, or a module that denies reflective access —
 * the detector degrades to comparing the recorded owner against the recording thread and says
 * so in the finding, at MEDIUM instead of CRITICAL.
 *
 * <p>The parameter types are {@link Object} rather than {@code java.lang.foreign.MemorySegment}
 * so this class compiles and runs on the library's JDK 21 baseline, where the FFM API is still
 * a preview. Callers on JDK 22+ pass real segments; nothing else changes.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = AsyncTestContext.confinedArenaThreadEscapeDetector();
 * try (Arena arena = Arena.ofConfined()) {
 *     d.recordArena(arena, "parseBuffer", Thread.currentThread());
 *     MemorySegment seg = arena.allocate(1024);
 *     d.recordAllocation(seg, arena, "parseBuffer", 1024);
 *     // ... on another thread ...
 *     d.recordAccess(seg, "parseBuffer", Thread.currentThread(), true);  // BUG: WrongThreadException
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-segment and per-arena state in ConcurrentHashMap; thread sets are "
        + "ConcurrentHashMap.newKeySet(); counters are LongAdder. The reflective Method "
        + "handles are resolved once into immutable statics and are themselves thread-safe.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ConfinedArenaThreadEscapeDetectorTest.java"
)
public final class ConfinedArenaThreadEscapeDetector {

    /**
     * Never started, never runs. Its only purpose is to be a thread that is definitionally not
     * the owner of any confined arena, so {@code isAccessibleBy(PROBE)} answers "is this segment
     * confined?" without needing the owner's identity.
     */
    private static final Thread PROBE = new Thread(() -> { }, "async-test-confinement-probe");

    private static final @Nullable Method IS_ACCESSIBLE_BY = lookup("isAccessibleBy", Thread.class);
    private static final @Nullable Method SCOPE            = lookup("scope");

    private static @Nullable Method lookup(String name, Class<?>... params) {
        try {
            Method m = Class.forName("java.lang.foreign.MemorySegment").getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException e) {
            return null;   // JDK 21 baseline, or reflective access denied — the fallback path covers it
        }
    }

    private static final class ArenaState {
        final String label;
        final @Nullable Long ownerThreadId;
        final String ownerThreadName;
        final AtomicBoolean closed        = new AtomicBoolean();
        final AtomicReference<String> closedByWrongThread = new AtomicReference<>();
        ArenaState(String label, @Nullable Thread owner) {
            this.label           = label;
            this.ownerThreadId   = owner == null ? null : owner.threadId();
            this.ownerThreadName = owner == null ? "unknown" : owner.getName();
        }
    }

    private static final class SegmentState {
        final String label;
        final @Nullable Integer arenaId;
        final long byteSize;
        final Set<String> accessingThreads = ConcurrentHashMap.newKeySet();
        final LongAdder wrongThreadAccesses = new LongAdder();
        final LongAdder afterCloseAccesses  = new LongAdder();
        final Set<String> offendingThreads  = ConcurrentHashMap.newKeySet();
        final AtomicBoolean confirmedConfined = new AtomicBoolean();
        final AtomicBoolean jvmAnswered       = new AtomicBoolean();
        SegmentState(String label, @Nullable Integer arenaId, long byteSize) {
            this.label    = label;
            this.arenaId  = arenaId;
            this.byteSize = byteSize;
        }
    }

    private final Map<Integer, ArenaState>   arenas   = new ConcurrentHashMap<>();
    private final Map<Integer, SegmentState> segments = new ConcurrentHashMap<>();

    /**
     * Register an arena and the thread that created it. For a confined arena that thread is the
     * only one permitted to access its segments or to close it.
     *
     * @param arena the {@code java.lang.foreign.Arena} instance, typed {@link Object} for the
     *              JDK 21 baseline (null-safe)
     * @param label human-readable label for triage (may be {@code null})
     * @param owner the creating thread
     */
    public void recordArena(@Nullable Object arena, @Nullable String label, @Nullable Thread owner) {
        if (arena == null) return;
        int id = System.identityHashCode(arena);
        final String lbl = label != null ? label : "Arena@" + id;
        arenas.computeIfAbsent(id, k -> new ArenaState(lbl, owner));
    }

    /**
     * Register a segment allocated from an arena. Recording the allocation is what lets a later
     * finding name the arena and its owner; access recording alone still detects the escape.
     *
     * @param segment  the {@code MemorySegment}, typed {@link Object} (null-safe)
     * @param arena    the arena it came from (may be {@code null} if unknown)
     * @param label    human-readable label for triage (may be {@code null})
     * @param byteSize the segment's size in bytes, for the report
     */
    public void recordAllocation(@Nullable Object segment, @Nullable Object arena,
                                 @Nullable String label, long byteSize) {
        if (segment == null) return;
        Integer arenaId = arena == null ? null : System.identityHashCode(arena);
        SegmentState s = stateFor(segment, label, arenaId, byteSize);
        probeConfinement(segment, s);
    }

    /**
     * Record an access to a segment. This is the call that detects the escape: the JVM is asked
     * whether {@code thread} may legally touch {@code segment}, and whether the segment's scope
     * is still alive.
     *
     * @param segment the {@code MemorySegment} being accessed, typed {@link Object} (null-safe)
     * @param label   human-readable label for triage (may be {@code null})
     * @param thread  the accessing thread
     * @param write   {@code true} for a write, {@code false} for a read; recorded for the report
     */
    public void recordAccess(@Nullable Object segment, @Nullable String label,
                             @Nullable Thread thread, boolean write) {
        if (segment == null || thread == null) return;
        SegmentState s = stateFor(segment, label, null, -1);
        s.accessingThreads.add(thread.getName() + (write ? " (write)" : " (read)"));
        probeConfinement(segment, s);

        Boolean accessible = isAccessibleBy(segment, thread);
        if (accessible != null) {
            s.jvmAnswered.set(true);
            if (!accessible) {
                s.wrongThreadAccesses.increment();
                s.offendingThreads.add(thread.getName());
            }
        } else if (s.arenaId != null) {
            // Fallback: no JVM answer available, compare against the recorded owner.
            ArenaState a = arenas.get(s.arenaId);
            if (a != null && a.ownerThreadId != null && a.ownerThreadId != thread.threadId()) {
                s.wrongThreadAccesses.increment();
                s.offendingThreads.add(thread.getName());
            }
        }

        Boolean alive = isAlive(segment);
        if (Boolean.FALSE.equals(alive) || (s.arenaId != null && isClosed(s.arenaId))) {
            s.afterCloseAccesses.increment();
            s.offendingThreads.add(thread.getName());
        }
    }

    /**
     * Record an arena being closed. Closing a confined arena from a thread other than its owner
     * throws {@code WrongThreadException}; closing at all makes every segment it allocated dead.
     *
     * @param arena  the arena being closed, typed {@link Object} (null-safe)
     * @param thread the closing thread
     */
    public void recordClose(@Nullable Object arena, @Nullable Thread thread) {
        if (arena == null) return;
        int id = System.identityHashCode(arena);
        ArenaState a = arenas.get(id);
        if (a == null) {
            final String lbl = "Arena@" + id;
            a = arenas.computeIfAbsent(id, k -> new ArenaState(lbl, thread));
        }
        a.closed.set(true);
        if (thread != null && a.ownerThreadId != null && a.ownerThreadId != thread.threadId()) {
            a.closedByWrongThread.compareAndSet(null, thread.getName());
        }
    }

    private boolean isClosed(int arenaId) {
        ArenaState a = arenas.get(arenaId);
        return a != null && a.closed.get();
    }

    private SegmentState stateFor(Object segment, @Nullable String label,
                                  @Nullable Integer arenaId, long byteSize) {
        int id = System.identityHashCode(segment);
        SegmentState s = segments.get(id);
        if (s == null) {
            final String lbl = label != null ? label : "MemorySegment@" + id;
            s = segments.computeIfAbsent(id, k -> new SegmentState(lbl, arenaId, byteSize));
        }
        return s;
    }

    /** Ask the JVM whether this segment rejects a thread that owns nothing. Confined ones do. */
    private static void probeConfinement(Object segment, SegmentState s) {
        Boolean probeAllowed = isAccessibleBy(segment, PROBE);
        if (probeAllowed != null) {
            s.jvmAnswered.set(true);
            if (!probeAllowed) s.confirmedConfined.set(true);
        }
    }

    private static @Nullable Boolean isAccessibleBy(Object segment, Thread thread) {
        if (IS_ACCESSIBLE_BY == null) return null;
        try {
            Object r = IS_ACCESSIBLE_BY.invoke(segment, thread);
            return (r instanceof Boolean b) ? b : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Boolean isAlive(Object segment) {
        if (SCOPE == null) return null;
        try {
            Object scope = SCOPE.invoke(segment);
            if (scope == null) return null;
            Method alive = scope.getClass().getMethod("isAlive");
            alive.setAccessible(true);
            Object r = alive.invoke(scope);
            return (r instanceof Boolean b) ? b : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Evaluate the observed state and produce a report. Idempotent: calling it N times on
     * quiescent state yields N identical reports.
     *
     * @return the report of confinement violations and use-after-close accesses
     */
    public Report analyze() {
        Report r = new Report();

        for (SegmentState s : segments.values()) {
            long wrong = s.wrongThreadAccesses.sum();
            if (wrong > 0) {
                boolean certain = s.jvmAnswered.get();
                add(r, s.label, certain ? IssueSeverity.CRITICAL : IssueSeverity.MEDIUM,
                    certain
                        ? String.format(
                            "CRITICAL: segment '%s' belongs to a confined arena and was accessed "
                            + "%d time(s) from thread(s) %s that do not own it. The JVM answered "
                            + "MemorySegment.isAccessibleBy(thread) = false, so this throws "
                            + "WrongThreadException — it is a defect no lock can fix.",
                            s.label, wrong, String.join(", ", s.offendingThreads))
                        : String.format(
                            "MEDIUM: segment '%s' was accessed %d time(s) from thread(s) %s that "
                            + "did not create its arena. MemorySegment.isAccessibleBy was not "
                            + "available on this JDK, so this compares recorded threads only — "
                            + "confirm the arena is confined before acting.",
                            s.label, wrong, String.join(", ", s.offendingThreads)),
                    s.accessingThreads.size());
            }

            long afterClose = s.afterCloseAccesses.sum();
            if (afterClose > 0) {
                add(r, s.label, IssueSeverity.CRITICAL, String.format(
                    "CRITICAL: segment '%s'%s was accessed %d time(s) after its arena was closed. "
                    + "The backing memory is freed at close, so this is a use-after-free reachable "
                    + "from Java: it throws IllegalStateException at best and reads freed memory "
                    + "at worst.",
                    s.label, size(s), afterClose), s.accessingThreads.size());
            }
        }

        for (ArenaState a : arenas.values()) {
            String wrongCloser = a.closedByWrongThread.get();
            if (wrongCloser != null) {
                add(r, a.label, IssueSeverity.HIGH, String.format(
                    "HIGH: arena '%s' was created by thread '%s' but closed by thread '%s'. "
                    + "Arena.ofConfined().close() from a non-owner throws WrongThreadException; "
                    + "if the arena is shared this is legal but still leaves every other thread's "
                    + "segments dead.",
                    a.label, a.ownerThreadName, wrongCloser), 2);
            }
        }
        return r;
    }

    private static void add(Report r, String label, IssueSeverity severity, String msg, int threadCount) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "ConfinedArenaThreadEscape",
                severity,
                msg,
                List.of(),
                Map.of("label", label, "threadCount", threadCount),
                Instant.now()));
    }

    /** Renders the recorded segment size for the report, or nothing when it was never recorded. */
    private static String size(SegmentState s) {
        return s.byteSize >= 0 ? " (" + s.byteSize + " bytes)" : "";
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
         * <p>Access after the arena closed, and access the JDK itself rejects through
         * {@code MemorySegment.isAccessibleBy}, are verdicts: the confinement was violated. The
         * weaker findings infer ownership from what was recorded and stay prompts.
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
            if (violations.isEmpty()) return "ConfinedArenaThreadEscape — clean";
            StringBuilder sb = new StringBuilder("CONFINED ARENA THREAD ESCAPE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Keep a confined arena's segments on the owning thread; do not store ")
              .append("them in a field, a cache or a collection that other threads read.\n")
              .append("    - If several threads genuinely need the same memory, allocate from ")
              .append("Arena.ofShared() and synchronize the accesses yourself — sharing removes ")
              .append("the confinement check, not the data race.\n")
              .append("    - Give each thread its own Arena.ofConfined() when the work is ")
              .append("per-thread; that is the cheapest arena and needs no coordination.\n")
              .append("    - Never let a segment outlive its arena: close the arena after every ")
              .append("thread that reads it has finished, not before.\n");
            return sb.toString();
        }
    }
}
