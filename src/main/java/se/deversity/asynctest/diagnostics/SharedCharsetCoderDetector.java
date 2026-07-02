package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link CharsetEncoder} / {@link CharsetDecoder} instances shared across threads.
 *
 * <p><strong>Why it matters.</strong> Both classes carry mutable internal coding
 * state — the current coder state machine, the malformed-input and
 * unmappable-character actions, and (for encoders) the replacement bytes —
 * that is advanced by every call to {@code encode()}/{@code decode()} and reset
 * by {@code reset()}/{@code flush()}. They are documented as <em>not</em>
 * thread-safe: "instances of this class are not thread-safe and appropriate
 * external synchronization is necessary." Concurrent use of one instance from
 * multiple threads interleaves state transitions, producing corrupted or
 * garbled output, or an {@code IllegalStateException} when one thread calls
 * {@code encode}/{@code decode} while another mid-flight call has left the
 * coder in {@code CODING} or {@code FLUSHED} state that the caller did not
 * expect.
 *
 * <p>The safe pattern is a fresh coder per thread — cheap to obtain via
 * {@code Charset.newEncoder()}/{@code Charset.newDecoder()} since
 * {@link java.nio.charset.Charset} itself is thread-safe and immutable — or a
 * {@link ThreadLocal}-scoped coder, or external synchronization if sharing is
 * unavoidable.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedCharsetCoderDetector();
 * d.recordAccess(encoder, "encode", Thread.currentThread());
 * d.recordAccess(decoder, "decode", Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedCharsetCoderDetectorTest.java"
)
public final class SharedCharsetCoderDetector {

    private static final class State {
        final String label;
        final String kind;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();
        final Set<String> operations           = ConcurrentHashMap.newKeySet();

        State(String label, String kind) {
            this.label = label;
            this.kind = kind;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a {@link CharsetEncoder} instance.
     *
     * @param encoder   the CharsetEncoder (null-safe)
     * @param operation descriptive operation label, e.g. "encode", "reset", "flush" (may be {@code null})
     * @param thread    accessing thread
     */
    public void recordAccess(CharsetEncoder encoder, String operation, Thread thread) {
        if (encoder == null) return;
        record(System.identityHashCode(encoder), operation, "CharsetEncoder", thread);
    }

    /**
     * Record an access to a {@link CharsetDecoder} instance.
     *
     * @param decoder   the CharsetDecoder (null-safe)
     * @param operation descriptive operation label, e.g. "decode", "reset", "flush" (may be {@code null})
     * @param thread    accessing thread
     */
    public void recordAccess(CharsetDecoder decoder, String operation, Thread thread) {
        if (decoder == null) return;
        record(System.identityHashCode(decoder), operation, "CharsetDecoder", thread);
    }

    private void record(int id, String operation, String kind, Thread thread) {
        if (thread == null) return;
        State s = instances.get(id);
        if (s == null) {
            final String label = kind + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label, kind));
        }
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
        if (operation != null) {
            s.operations.add(operation);
        }
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "%s '%s' accessed from %d threads (%s) via operations %s — %s carries mutable "
                            + "internal coding state and is not thread-safe; concurrent use corrupts "
                            + "the coder state, garbles output, or throws IllegalStateException.",
                    s.kind,
                    s.label,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    s.operations,
                    s.kind);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedCharsetCoder",
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
            if (violations.isEmpty()) return "SHARED CHARSET ENCODER/DECODER — clean";
            StringBuilder sb = new StringBuilder("SHARED CHARSET ENCODER/DECODER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use Charset.newEncoder()/newDecoder() to create one coder per thread; "
                      + "Charset itself is thread-safe and cheap to derive coders from.\n")
              .append("    - Or scope the coder to a ThreadLocal.\n")
              .append("    - Or add external synchronization if sharing one instance is unavoidable.\n");
            return sb.toString();
        }
    }
}
