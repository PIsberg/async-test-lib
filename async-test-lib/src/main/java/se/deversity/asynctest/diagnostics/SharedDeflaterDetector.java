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
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Detects {@link Deflater} / {@link Inflater} instances shared across threads.
 *
 * <p><strong>Why it matters.</strong> Both classes wrap a native zlib stream
 * whose state machine ({@code setInput} then {@code deflate}/{@code inflate}
 * until {@code finished}) is advanced by every call. They are <em>not</em>
 * thread-safe. Concurrent use of
 * one instance interleaves bytes from different logical streams, producing
 * corrupt/undecompressable output or a {@code NullPointerException} from the
 * native layer once one thread calls {@code end()} while another is mid-stream.
 *
 * <p>There is a second hazard: {@code Deflater}/{@code Inflater} hold off-heap
 * memory that is only released by {@code end()}. Sharing one instance encourages
 * "someone else will close it" ownership confusion, which is also a resource
 * leak — but the thread-safety violation is the more acute bug, so findings are
 * reported here rather than via {@link ResourceLeakDetector}.
 *
 * <p>The safe pattern is one instance per thread (and a matching {@code end()}
 * in a {@code finally}), or a fresh instance per compression unit.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedDeflaterDetector();
 * d.recordAccess(deflater, "response-gzip", Thread.currentThread());
 * d.recordAccess(inflater, "request-gunzip", Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedDeflaterDetectorTest.java"
)
public final class SharedDeflaterDetector {

    private static final class State {
        final String label;
        final String kind;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label, String kind) {
            this.label = label;
            this.kind = kind;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a {@link Deflater} instance.
     *
     * @param deflater the Deflater (null-safe)
     * @param name     descriptive label for reports (may be {@code null})
     * @param thread   accessing thread
     */
    public void recordAccess(Deflater deflater, String name, Thread thread) {
        if (deflater == null) return;
        record(System.identityHashCode(deflater), name, "Deflater", thread);
    }

    /**
     * Record an access to an {@link Inflater} instance.
     *
     * @param inflater the Inflater (null-safe)
     * @param name     descriptive label for reports (may be {@code null})
     * @param thread   accessing thread
     */
    public void recordAccess(Inflater inflater, String name, Thread thread) {
        if (inflater == null) return;
        record(System.identityHashCode(inflater), name, "Inflater", thread);
    }

    private void record(int id, String name, String kind, Thread thread) {
        if (thread == null) return;
        State s = instances.get(id);
        if (s == null) {
            final String label = (name != null) ? name : kind + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label, kind));
        }
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "%s '%s' accessed from %d threads (%s) — java.util.zip %s wraps a "
                            + "stateful native zlib stream and is not thread-safe; concurrent use "
                            + "corrupts output or crashes when one thread calls end() mid-stream.",
                    s.kind,
                    s.label,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    s.kind);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedDeflater",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "kind", s.kind,
                            "threadCount", s.accessingThreadIds.size()),
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
            if (violations.isEmpty()) return "SHARED DEFLATER/INFLATER — clean";
            StringBuilder sb = new StringBuilder("SHARED DEFLATER/INFLATER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use one Deflater/Inflater per thread, with end() in a finally block.\n")
              .append("    - Or create a fresh instance per compression unit.\n")
              .append("    - Never share one native zlib stream across concurrent callers.\n");
            return sb.toString();
        }
    }
}
