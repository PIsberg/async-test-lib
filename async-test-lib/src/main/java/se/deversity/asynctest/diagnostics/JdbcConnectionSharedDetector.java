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
import java.util.Set;
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
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap-backed JDBC-resource tracking; per-resource State holds ConcurrentHashMap.newKeySet() for accessing threads.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/JdbcConnectionSharedDetectorTest.java"
)
public final class JdbcConnectionSharedDetector {

    private static final class State {
        final String label;
        final String type;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label, String type) {
            this.label = label;
            this.type = type;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

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

        int id = System.identityHashCode(resource);
        State s = instances.get(id);
        if (s == null) {
            final String finalType = type;
            s = instances.computeIfAbsent(id, k -> new State(
                    (name != null) ? name : finalType + "@" + k,
                    finalType));
        }
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the analyze
     */

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
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
                            + "thread safety on %s; %s. Use a per-thread Connection (pool checkout).",
                    s.label,
                    s.type,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
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
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether there are issues} */
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
