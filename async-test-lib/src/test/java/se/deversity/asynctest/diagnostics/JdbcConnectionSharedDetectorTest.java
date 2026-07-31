package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JdbcConnectionSharedDetector}.
 *
 * <p>JDBC interfaces are implemented here via JDK dynamic proxies — there's no
 * need to spin up a real database connection just to exercise the type-checking
 * logic in {@code recordAccess}.
 */
class JdbcConnectionSharedDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new JdbcConnectionSharedDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void sharedConnectionIsFlagged() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        Connection c = proxy(Connection.class);
        d.recordAccess(c, "tx-conn", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(c, "tx-conn", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("tx-conn"));
        assertTrue(msg.contains("Connection"));
        assertTrue(msg.contains("transaction"),
                "Connection-specific risk text must appear: " + msg);
        assertEquals(1, report.structuredViolations.size());
        assertEquals("JdbcConnectionShared", report.structuredViolations.get(0).detector());
        assertEquals("Connection", report.structuredViolations.get(0).attributes().get("type"));
    }

    @Test
    void sharedPreparedStatementIsFlagged() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        PreparedStatement ps = proxy(PreparedStatement.class);
        d.recordAccess(ps, "insert-ps", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(ps, "insert-ps", Thread.currentThread()));
        t.start();
        t.join();
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("PreparedStatement"));
        assertTrue(msg.contains("parameter bindings"),
                "PreparedStatement-specific risk text must appear: " + msg);
    }

    @Test
    void sharedResultSetIsFlagged() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        ResultSet rs = proxy(ResultSet.class);
        d.recordAccess(rs, "rs", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(rs, "rs", Thread.currentThread()));
        t.start();
        t.join();
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("ResultSet"));
        assertTrue(msg.contains("cursor"),
                "ResultSet-specific risk text must appear: " + msg);
    }

    @Test
    void sharedStatementIsFlagged() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        Statement st = proxy(Statement.class);
        d.recordAccess(st, "st", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(st, "st", Thread.currentThread()));
        t.start();
        t.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void nonJdbcObjectsAreIgnored() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        Object o = new Object();
        d.recordAccess(o, "not-jdbc", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(o, "not-jdbc", Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues(),
                "Non-JDBC objects must not appear in the detector's bookkeeping");
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new JdbcConnectionSharedDetector();
        Connection c = proxy(Connection.class);
        for (int i = 0; i < 5; i++) {
            d.recordAccess(c, "solo", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new JdbcConnectionSharedDetector();
        d.recordAccess(null, "x", Thread.currentThread());
        d.recordAccess(proxy(Connection.class), "x", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void fixHintMentionsConnectionPools() throws Exception {
        var d = new JdbcConnectionSharedDetector();
        Connection c = proxy(Connection.class);
        d.recordAccess(c, "x", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(c, "x", Thread.currentThread()));
        t.start();
        t.join();
        String reportText = d.analyze().toString();
        assertTrue(reportText.contains("HikariCP"),
                "Fix hint should mention a concrete connection-pool option");
        assertTrue(reportText.contains("per worker thread"),
                "Fix hint should clarify per-thread checkout");
    }

    /** Build a no-op proxy implementing the given JDBC interface — no real DB needed. */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    if (method.getName().equals("equals")) return p == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (method.getName().equals("toString")) return iface.getSimpleName() + "@proxy";
                    return null;
                });
    }
}
