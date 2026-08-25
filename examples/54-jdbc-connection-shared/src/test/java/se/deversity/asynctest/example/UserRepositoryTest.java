package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UserRepository demonstrating the JdbcConnectionSharedDetector.
 *
 * The concurrent test shows how sharing a single Connection across threads
 * is flagged as a concurrency hazard.
 */
class UserRepositoryTest {

    private UserRepository repository;
    private Connection mockConnection;

    @BeforeEach
    void setUp() {
        // Create a simple proxy Connection for demonstration purposes
        mockConnection = createMockConnection();
        repository = new UserRepository(mockConnection);
    }

    @Test
    void test_singleThread_findById_works() {
        assertNotNull(repository);
        // Single-threaded usage is fine — no sharing occurs
        assertNotNull(repository.getConnection());
    }

    @Test
    void test_singleThread_save_works() {
        // Single-threaded save does not trigger the detector
        assertDoesNotThrow(() -> repository.save("Alice"));
    }

    @Disabled("Remove @Disabled to see bug detected by JdbcConnectionSharedDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectJdbcConnectionShared = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        // Record the shared connection being accessed from this thread
        AsyncTestContext.jdbcConnectionSharedDetector()
                .recordAccess(mockConnection, "UserRepository.connection", Thread.currentThread());

        // Simulate concurrent read/write — all threads hit the same Connection
        repository.findById(1);
        repository.save("User-" + Thread.currentThread().getId());
    }

    // ---------------------------------------------------------------------------
    // Minimal stub Connection — avoids a real JDBC driver dependency
    // ---------------------------------------------------------------------------
    private static Connection createMockConnection() {
        return (Connection) Proxy.newProxyInstance(
                UserRepositoryTest.class.getClassLoader(),
                new Class[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("createStatement".equals(name)) {
                            return createMockStatement();
                        }
                        if ("isClosed".equals(name)) return false;
                        if ("close".equals(name)) return null;
                        return null;
                    }
                });
    }

    private static Statement createMockStatement() {
        return (Statement) Proxy.newProxyInstance(
                UserRepositoryTest.class.getClassLoader(),
                new Class[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeQuery".equals(name)) return createMockResultSet();
                    if ("executeUpdate".equals(name)) return 1;
                    if ("close".equals(name)) return null;
                    return null;
                });
    }

    private static ResultSet createMockResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                UserRepositoryTest.class.getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) return false;
                    if ("close".equals(name)) return null;
                    return null;
                });
    }
}
