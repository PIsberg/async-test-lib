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
import java.util.zip.Checksum;

/**
 * Detects {@link Checksum} implementations (e.g. {@code CRC32}, {@code CRC32C},
 * {@code Adler32}) shared across threads.
 *
 * <p><strong>Why it matters.</strong> A {@code Checksum} accumulates internal
 * running state with every {@code update()} call; {@code getValue()} reads that
 * accumulated state and {@code reset()} clears it. None of the JDK
 * implementations are thread-safe. Unsynchronized concurrent {@code update()}/{@code getValue()}/
 * {@code reset()} calls from multiple threads interleave updates to the same
 * accumulator, silently producing a wrong checksum value — there is no exception,
 * no crash, just data-integrity corruption that surfaces later as a checksum
 * mismatch far from the code that caused it.
 *
 * <p>The detector observes sharing — which threads touched the instance — not
 * locks: a shared checksum guarded by correct external synchronization is
 * flagged all the same. Treat a finding as a prompt to verify that
 * synchronization exists, or to move to a per-thread instance.
 *
 * <p>The safe pattern is one {@code Checksum} instance per thread (a
 * {@link ThreadLocal} works well), or computing a checksum per-chunk on each
 * thread and combining the partial results afterward (CRC32 supports combining
 * via third-party utilities such as Guava's {@code Hashing.crc32()} combiners or
 * zlib's {@code crc32_combine}), or synchronizing all access externally.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedChecksumDetector();
 * d.recordAccess(crc32, "update", Thread.currentThread());
 * d.recordAccess(crc32, "getValue", Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedChecksumDetectorTest.java"
)
public final class SharedChecksumDetector {

    private static final class State {
        final String label;
        final Set<String>  operations           = ConcurrentHashMap.newKeySet();
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label) {
            this.label = label;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a {@link Checksum} instance.
     *
     * @param checksum  the Checksum (null-safe)
     * @param operation descriptive operation label, e.g. {@code "update"},
     *                  {@code "getValue"}, {@code "reset"} (may be {@code null})
     * @param thread    accessing thread
     */
    public void recordAccess(Checksum checksum, String operation, Thread thread) {
        if (checksum == null || thread == null) return;
        int id = System.identityHashCode(checksum);
        State s = instances.get(id);
        if (s == null) {
            final String label = checksum.getClass().getSimpleName() + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label));
        }
        if (operation != null) s.operations.add(operation);
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "Checksum '%s' accessed from %d threads (%s) via %s — java.util.zip "
                            + "Checksum implementations accumulate mutable running state and are "
                            + "not thread-safe; unsynchronized concurrent update()/getValue()/reset() calls "
                            + "produce wrong checksum values with no exception"
                            + " (the detector observes sharing, not locks — verify external"
                            + " synchronization or use a per-thread instance).",
                    s.label,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    String.join(", ", s.operations));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedChecksum",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SHARED CHECKSUM — clean";
            StringBuilder sb = new StringBuilder("SHARED CHECKSUM DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use one Checksum instance per thread (e.g. via ThreadLocal).\n")
              .append("    - Or compute a checksum per-chunk on each thread and combine the "
                      + "partial results afterward.\n")
              .append("    - Or synchronize all access to the shared Checksum instance externally.\n");
            return sb.toString();
        }
    }
}
