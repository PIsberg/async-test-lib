package com.example.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates connection pooling stub behavior in {@link StubDataSource}.
 */
class StubDataSourceTest {

    @Test
    @DisplayName("createdConnections tracks physical connection allocations")
    void tracksCreatedConnections() throws SQLException {
        StubDataSource ds = new StubDataSource();
        assertEquals(0, ds.createdConnections());

        Connection conn1 = ds.getConnection();
        assertNotNull(conn1);
        assertEquals(1, ds.createdConnections());

        Connection conn2 = ds.getConnection("user", "pass");
        assertNotNull(conn2);
        assertEquals(2, ds.createdConnections());
    }

    @Test
    @DisplayName("connection proxy handles pool-expected contract methods")
    void connectionProxySatisfiesPoolExpectations() throws SQLException {
        StubDataSource ds = new StubDataSource();
        Connection conn = ds.getConnection();

        assertTrue(conn.isValid(1));
        assertFalse(conn.isClosed());
        assertFalse(conn.getAutoCommit());
        assertFalse(conn.isReadOnly());
        assertEquals(0, conn.getNetworkTimeout());
        assertEquals(0, conn.getTransactionIsolation());
        assertEquals(0, conn.getHoldability());
        assertNull(conn.getCatalog());
        assertNull(conn.getSchema());

        assertSame(conn, conn.unwrap(Connection.class));
        assertTrue(conn.isWrapperFor(Connection.class));

        assertEquals(conn, conn);
        Connection other = ds.getConnection();
        assertFalse(conn.equals(other));
        assertEquals(System.identityHashCode(conn), conn.hashCode());
        assertTrue(conn.toString().startsWith("StubConnection@"));
    }

    @Test
    @DisplayName("dataSource standard methods behave safely")
    void dataSourceStandardMethods() {
        StubDataSource ds = new StubDataSource();
        assertNull(ds.getLogWriter());
        ds.setLogWriter(null);

        assertEquals(0, ds.getLoginTimeout());
        ds.setLoginTimeout(30);

        assertEquals(Logger.getGlobal(), ds.getParentLogger());
        assertFalse(ds.isWrapperFor(DataSourceTestHelper.class));
        assertThrows(SQLException.class, () -> ds.unwrap(DataSourceTestHelper.class));
    }

    private interface DataSourceTestHelper {
    }
}
