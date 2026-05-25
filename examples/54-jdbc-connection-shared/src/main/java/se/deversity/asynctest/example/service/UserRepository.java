package se.deversity.asynctest.example.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * A user repository that holds a single shared Connection — not thread-safe.
 *
 * BUG: java.sql.Connection is not thread-safe. Multiple threads sharing the same
 * Connection instance will corrupt each other's queries, ResultSets, and transactions.
 */
public class UserRepository {

    // BUG: shared across all threads — Connection is not thread-safe
    private final Connection connection;

    public UserRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Finds a user by id. Uses the shared connection directly.
     * Under concurrent access, another thread may close the ResultSet mid-read.
     */
    public String findById(int id) {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = " + id);
            if (rs.next()) {
                return rs.getString("name");
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Query failed", e);
        }
    }

    /**
     * Saves a new user. Uses the shared connection directly.
     * Under concurrent access, two threads may interleave inside the same transaction.
     */
    public void save(String name) {
        try {
            Statement stmt = connection.createStatement();
            stmt.executeUpdate("INSERT INTO users (name) VALUES ('" + name + "')");
        } catch (Exception e) {
            throw new RuntimeException("Insert failed", e);
        }
    }

    public Connection getConnection() {
        return connection;
    }
}
