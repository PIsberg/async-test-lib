package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link java.nio.Buffer} / {@link java.nio.ByteBuffer} instances
 * shared across threads without coordination.
 *
 * <p><strong>Why it matters.</strong> Every {@code Buffer} carries mutable
 * cursor state — {@code position}, {@code limit}, and {@code mark} — that is
 * advanced by relative operations: relative {@code get}/{@code put},
 * {@code flip()}, {@code rewind()}, {@code clear()}, {@code mark()}/
 * {@code reset()}, and the single-argument {@code position(int)}/
 * {@code limit(int)} setters. None of this is synchronized. When two threads
 * perform relative operations on the same instance concurrently, one thread's
 * {@code flip()} or {@code get()} silently moves the cursor out from under the
 * other, producing {@link java.nio.BufferUnderflowException} /
 * {@link java.nio.BufferOverflowException}, or — worse — no exception at all,
 * just interleaved bytes from two logical streams read as one.
 *
 * <p>Absolute operations ({@code get(int)}, {@code put(int, ...)}) do not
 * touch {@code position}/{@code limit}/{@code mark} and are safe to call
 * concurrently on a shared instance (module the backing storage itself
 * needing external synchronization for read/write races, which this detector
 * does not attempt to cover). This detector therefore only flags concurrent
 * <em>position-mutating</em> access; absolute-only access from many threads is
 * reported as context, never as a violation.
 *
 * <p>The safe pattern is to give each thread its own view via
 * {@code duplicate()} or {@code slice()} (independent position/limit/mark over
 * the same backing storage), to restrict concurrent callers to absolute
 * operations, or to guard the shared instance with external synchronization.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedByteBufferDetector();
 * d.recordPositionalAccess(buffer, "flip");
 * d.recordAbsoluteAccess(buffer, "get(int)");
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name and operation sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedByteBufferDetectorTest.java"
)
public final class SharedByteBufferDetector {

    private static final class State {
        final String label;
        final String kind;
        final Set<Long>   positionalThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> positionalThreadNames = ConcurrentHashMap.newKeySet();
        final Set<String> positionalOperations  = ConcurrentHashMap.newKeySet();
        final Set<Long>   absoluteThreadIds     = ConcurrentHashMap.newKeySet();
        final Set<String> absoluteThreadNames   = ConcurrentHashMap.newKeySet();
        final Set<String> absoluteOperations    = ConcurrentHashMap.newKeySet();

        State(String label, String kind) {
            this.label = label;
            this.kind = kind;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record a position-mutating access to a buffer instance — a relative
     * {@code get}/{@code put}, {@code flip()}, {@code rewind()},
     * {@code clear()}, {@code mark()}/{@code reset()}, or single-argument
     * {@code position(int)}/{@code limit(int)} call.
     *
     * @param buffer    the {@code java.nio.Buffer} (or subclass) instance (null-safe)
     * @param operation descriptive label of the operation (e.g. {@code "flip"}, may be {@code null})
     */
    public void recordPositionalAccess(Object buffer, String operation) {
        if (buffer == null) return;
        State s = resolve(buffer);
        Thread thread = Thread.currentThread();
        s.positionalThreadIds.add(thread.getId());
        s.positionalThreadNames.add(thread.getName());
        s.positionalOperations.add(operation != null ? operation : "unknown");
    }

    /**
     * Record an absolute (position-independent) access to a buffer instance —
     * {@code get(int)} or {@code put(int, ...)}. These do not mutate
     * {@code position}/{@code limit}/{@code mark} and are recorded only as
     * context for reports, never as a violation on their own.
     *
     * @param buffer    the {@code java.nio.Buffer} (or subclass) instance (null-safe)
     * @param operation descriptive label of the operation (e.g. {@code "get(int)"}, may be {@code null})
     */
    public void recordAbsoluteAccess(Object buffer, String operation) {
        if (buffer == null) return;
        State s = resolve(buffer);
        Thread thread = Thread.currentThread();
        s.absoluteThreadIds.add(thread.getId());
        s.absoluteThreadNames.add(thread.getName());
        s.absoluteOperations.add(operation != null ? operation : "unknown");
    }

    private State resolve(Object buffer) {
        int id = System.identityHashCode(buffer);
        State s = instances.get(id);
        if (s == null) {
            final String kind = buffer.getClass().getSimpleName();
            final String label = kind + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label, kind));
        }
        return s;
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.positionalThreadIds.size() <= 1) continue;
            StringBuilder msg = new StringBuilder(String.format(
                    "%s '%s' had position-mutating operations (%s) performed by %d threads (%s) — "
                            + "java.nio.Buffer instances carry mutable position/limit/mark state that is not "
                            + "thread-safe; concurrent relative get/put, flip(), rewind(), clear(), mark()/reset(), "
                            + "or position(int)/limit(int) calls corrupt that state, causing "
                            + "BufferUnderflowException/BufferOverflowException or silently interleaved data.",
                    s.kind,
                    s.label,
                    String.join(", ", s.positionalOperations),
                    s.positionalThreadIds.size(),
                    String.join(", ", s.positionalThreadNames)));
            if (!s.absoluteThreadIds.isEmpty()) {
                msg.append(String.format(
                        " Also observed absolute operations (%s) from %d thread(s) (%s), which do not "
                                + "touch position/limit/mark and are safe on their own.",
                        String.join(", ", s.absoluteOperations),
                        s.absoluteThreadIds.size(),
                        String.join(", ", s.absoluteThreadNames)));
            }
            r.violations.add(msg.toString());
            r.structuredViolations.add(new Violation(
                    "SharedByteBuffer",
                    IssueSeverity.HIGH,
                    msg.toString(),
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "kind", s.kind,
                            "positionalThreadCount", s.positionalThreadIds.size(),
                            "positionalOperations", String.join(", ", s.positionalOperations),
                            "absoluteThreadCount", s.absoluteThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SHARED BYTE BUFFER — clean";
            StringBuilder sb = new StringBuilder("SHARED BYTE BUFFER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Give each thread its own view via duplicate() or slice() "
                      + "(independent position/limit/mark over the same backing storage).\n")
              .append("    - Or restrict concurrent callers to absolute get(int)/put(int, ...) operations.\n")
              .append("    - Or guard the shared instance with external synchronization.\n");
            return sb.toString();
        }
    }
}
