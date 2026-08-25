package com.example.corpus;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * The smallest {@link DataSource} HikariCP will pool: it hands back inert connections.
 *
 * <p>This is test scaffolding, not a corpus subject, and the distinction is the reason the
 * HikariCP row lives in the recording lane rather than the unmodified ones. Those lanes claim
 * that no line of the subject is ours, and a pool cannot be exercised without something to pool.
 * The recording lane's body is ours by construction, so the claim it makes is a different one:
 * the code deciding which thread gets which connection, and when, is still entirely HikariCP's.
 *
 * <p>A database would answer the same question with a network round trip per call, and the pool's
 * behaviour under contention would then be a wall-clock race - the flakiness a measurement must
 * not depend on. These connections return instantly and block on nothing, so the only thing left
 * to observe is the pool's own checkout discipline.
 *
 * <p>Implemented as a dynamic proxy because a hand-written stub of {@link Connection} is ~50
 * unimplemented methods, and every one of them would be a place for a typo to change what is
 * being measured. Defaults are chosen for what a pool asks: it is open, valid, and reports no
 * network timeout.
 */
final class StubDataSource implements DataSource {

    private final AtomicInteger created = new AtomicInteger();

    /** {@return how many physical connections the pool actually opened} */
    int createdConnections() {
        return created.get();
    }

    @Override
    public Connection getConnection() {
        created.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                StubDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> Boolean.TRUE;
                    case "isClosed", "getAutoCommit", "isReadOnly" -> Boolean.FALSE;
                    case "getNetworkTimeout", "getTransactionIsolation", "getHoldability" -> 0;
                    case "getCatalog", "getSchema" -> null;
                    // A proxy is not identity-equal to itself through equals() unless we say so,
                    // and the pool keeps connections in collections.
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "StubConnection@" + System.identityHashCode(proxy);
                    // unwrap is how a caller reaches the physical connection through the pool's
                    // own wrapper, which is what makes the reuse visible at all.
                    case "unwrap" -> proxy;
                    case "isWrapperFor" -> Boolean.TRUE;
                    default -> defaultFor(method.getReturnType());
                });
    }

    /** {@return a harmless value of {@code type}, so an unanticipated call cannot throw} */
    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        return type == long.class ? 0L : type == double.class ? 0.0d : 0;
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // nothing to log
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // nothing to time out
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
