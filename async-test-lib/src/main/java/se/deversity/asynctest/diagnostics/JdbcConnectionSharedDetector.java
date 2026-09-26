package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link Connection}, {@link Statement}, {@link PreparedStatement},
 * or {@link ResultSet} instances accessed from more than one thread.
 *
 * <p><strong>Why it matters.</strong> The JDBC specification does NOT require
 * any of these to be thread-safe. The vast majority of production drivers
 * (PostgreSQL JDBC, MySQL Connector/J, Oracle JDBC, MariaDB, H2) explicitly
 * document that a single Connection must be used by at most one thread at a
 * time. Concurrent access produces driver-specific outcomes:
 *
 * <ul>
 *   <li>Mixed result-set cursors — two threads execute different queries and
 *       receive each other's rows.</li>
 *   <li>Protocol corruption — interleaved statement bytes on the wire cause
 *       the driver to throw {@code SQLException} with cryptic messages like
 *       "unexpected packet" or "stream closed mid-message".</li>
 *   <li>Transaction state leakage — commit/rollback from one thread affects
 *       the other thread's in-flight statement.</li>
 *   <li>Silent data corruption — in rare cases parameter bindings from one
 *       thread land on another thread's prepared statement.</li>
 * </ul>
 *
 * <p>The correct pattern is a per-thread connection (typically via a connection
 * pool like HikariCP/Tomcat-DBCP, which gives each thread its own checked-out
 * Connection for the duration of a request).
 *
 * <p><strong>A pool is the fix, not the defect.</strong> The rule is one thread at a time, not
 * one thread ever, and a pool hands the same physical connection to different threads over its
 * lifetime by design. Tell the detector when a thread lets go, with
 * {@link #recordRelease(Object, Thread)}, and it reports only threads that held the resource
 * simultaneously. A resource for which no release is ever recorded keeps the older model, where
 * every accessing thread counts - a test that never modelled ownership has said nothing about
 * whether its accesses overlapped, and the safe reading of that silence is the stricter one.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new JdbcConnectionSharedDetector();
 * d.recordAccess(connection, "tx-conn", Thread.currentThread());
 * try (Statement s = connection.createStatement()) {
 *     d.recordAccess(s, "tx-stmt", Thread.currentThread());
 *     // ...
 * }
 * }</pre>
 *
 * <p>With a pool, unwrap to the physical connection so the reuse is visible at all - each
 * checkout hands back a fresh proxy, and tracking those would make every checkout look like a
 * different resource:
 * <pre>{@code
 * try (Connection pooled = dataSource.getConnection()) {
 *     Connection physical = pooled.unwrap(Connection.class);
 *     d.recordAccess(physical, "pool-conn", Thread.currentThread());
 *     // ... use it ...
 *     d.recordRelease(physical, Thread.currentThread());
 * }
 * }</pre>
 *
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java"
)
public final class JdbcConnectionSharedDetector {

    private static final class State extends SelfGuard.ThreadTrackedInstance {
        final String label;
        final String type;

        /**
         * Threads that currently hold the resource, when the caller models handoff.
         *
         * <p>Empty unless {@link #recordRelease} is used. A resource whose ownership is never
         * released stays on the older model, where every accessing thread counts. Keyed by
         * thread id, each with the label a report prints for it, taken while the thread is alive.
         */
        final Map<Long, String> currentHolders = new ConcurrentHashMap<>();

        /** Whether the caller ever said a thread was done with this resource. */
        volatile boolean ownershipModelled;

        /**
         * The threads seen holding it at the same time, which is the actual defect, by thread id:
         * keyed by name, unnamed (virtual) threads merged into one blank entry and the report
         * undercounted them.
         */
        final Map<Long, String> overlappingThreads = new ConcurrentHashMap<>();

        State(String label, String type) {
            this.label = label;
            this.type = type;
        }
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a JDBC resource. Non-JDBC objects are silently
     * ignored — the detector is type-specific.
     *
     * @param resource the JDBC object (null-safe; non-JDBC types ignored)
     * @param name     descriptive label (may be {@code null})
     * @param thread   accessing thread
     */
    public void recordAccess(Object resource, String name, Thread thread) {
        if (resource == null || thread == null) return;
        String type;
        if      (resource instanceof Connection)        type = "Connection";
        else if (resource instanceof PreparedStatement) type = "PreparedStatement";
        else if (resource instanceof Statement)         type = "Statement";
        else if (resource instanceof ResultSet)         type = "ResultSet";
        else return;

        IdentityKey id = new IdentityKey(resource);
        State s = instances.get(id);
        if (s == null) {
            final String finalType = type;
            s = instances.computeIfAbsent(id, k -> new State(
                    (name != null) ? name : finalType + "@" + k.hashCode(),
                    finalType));
        }
        s.noteAccess(resource, thread);
        long threadId = thread.threadId();
        if (!s.currentHolders.containsKey(threadId)) {
            String threadName = thread.getName();
            s.currentHolders.put(threadId, threadName.isEmpty() ? "#" + threadId : threadName);
        }
        if (s.currentHolders.size() >= 2) {
            // Two threads holding at once is the defect itself, so it is recorded when it
            // happens rather than inferred afterwards from a set of thread ids that has no
            // notion of when each one held the resource. Every holder at this moment is part
            // of it, the thread that took the resource first as much as the one that joined.
            s.overlappingThreads.putAll(s.currentHolders);
        }
    }

    /**
     * Record that {@code thread} is done with a JDBC resource.
     *
     * <p><strong>What this changes.</strong> The JDBC contract is that a Connection is used by at
     * most one thread <em>at a time</em>, not that it is only ever touched by one thread. A
     * connection pool exists precisely to hand the same physical connection to different threads
     * over its lifetime, one at a time, and that is correct usage. Without a way to say when a
     * thread let go, a pooled connection and a shared one look identical - the same set of thread
     * ids - so the pool drew a HIGH finding for working exactly as designed.
     *
     * <p>Call this when the resource leaves the thread's ownership: after {@code close()} on a
     * pooled handle, at the end of a checkout, or wherever the handoff happens. Then a finding
     * means two threads held it simultaneously, which is the real defect.
     *
     * <p><strong>Callers who do not use it are unaffected.</strong> A resource for which no
     * release is ever recorded keeps the older model, where every accessing thread counts, so
     * existing tests report exactly what they reported before. That is deliberate: a test that
     * never modelled ownership has told the detector nothing about when its accesses overlapped,
     * and the safe reading of that silence is the stricter one.
     *
     * @param resource the JDBC object (null-safe; non-JDBC types ignored)
     * @param thread   the thread relinquishing it
     * @since 1.9.8
     */
    public void recordRelease(Object resource, Thread thread) {
        if (resource == null || thread == null) return;
        State s = instances.get(new IdentityKey(resource));
        if (s == null) return;
        s.ownershipModelled = true;
        s.currentHolders.remove(thread.threadId());
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.threadCount() <= 1) continue;
            // Ownership was modelled and no two threads ever held it at once: this is a pooled
            // handle doing its job, handed to one thread at a time. Reporting it would flag the
            // documented fix for the defect this detector exists to find.
            if (s.ownershipModelled && s.overlappingThreads.isEmpty()) continue;
            // Every use held the resource's own monitor or a declared lock: serialised by hand.
            if (!s.sawUnguardedSharing()) continue;
            String specificRisk = switch (s.type) {
                case "Connection" -> "concurrent statement execution on one Connection corrupts the "
                        + "driver's protocol state and leaks transaction boundaries between threads";
                case "PreparedStatement" -> "parameter bindings from one thread can land on another "
                        + "thread's execution, silently corrupting query inputs";
                case "Statement" -> "concurrent execute() calls share the driver's wire-protocol state "
                        + "and produce interleaved bytes";
                case "ResultSet" -> "the cursor position is per-instance; concurrent next()/get*() calls "
                        + "race on the same cursor and produce mixed-row reads";
                default -> "JDBC resources are not thread-safe per spec";
            };
            String msg = String.format(
                    "'%s' (%s) accessed from %d threads (%s) — JDBC spec does NOT require "
                            + "thread safety on %s; %s. Use a per-thread Connection (pool checkout)." + SelfGuard.REPORT_NOTE,
                    s.label,
                    s.type,
                    s.ownershipModelled ? s.overlappingThreads.size() : s.threadCount(),
                    String.join(", ", s.ownershipModelled
                            ? s.overlappingThreads.values() : s.threadNames()),
                    s.type,
                    specificRisk);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "JdbcConnectionShared",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "type", s.type,
                            "threadCount", s.threadCount()),
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
            if (violations.isEmpty()) return "JDBC RESOURCES — clean";
            StringBuilder sb = new StringBuilder("SHARED JDBC RESOURCES DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Check out one Connection per worker thread from a pool (HikariCP, c3p0, DBCP).\n")
              .append("    - Never store a Connection / Statement / ResultSet in a field that crosses threads.\n")
              .append("    - For batch work, use a ThreadLocal<Connection> with explicit close in finally.\n")
              .append("    - If you need to publish results across threads, copy rows into a thread-safe\n")
              .append("      data structure on the producer side; never pass the live ResultSet.\n");
            return sb.toString();
        }
    }
}
