package se.deversity.asynctest.example.service;

import java.time.Duration;

/**
 * A stand-in for {@code StructuredTaskScope.Configuration}, with the property that matters:
 * every {@code withX} returns a <em>new</em> instance and mutates nothing.
 *
 * <p>That immutability is what makes the JEP 525 lambda easy to get wrong. The old constructor
 * form had nothing to drop on the floor; a {@code UnaryOperator<Configuration>} that ignores its
 * parameter, or that calls {@code withTimeout} for its side effect and returns something else,
 * compiles, runs, and configures nothing at all.
 */
public record ScopeSettings(String name, Duration timeout) {

    /** No name, no deadline - what a scope has before the lambda touches it. */
    public static ScopeSettings defaults() {
        return new ScopeSettings(null, null);
    }

    /** {@return a copy carrying {@code timeout}} The receiver is unchanged. */
    public ScopeSettings withTimeout(Duration newTimeout) {
        return new ScopeSettings(name, newTimeout);
    }

    /** {@return a copy carrying {@code name}} The receiver is unchanged. */
    public ScopeSettings withName(String newName) {
        return new ScopeSettings(newName, timeout);
    }

    /** {@return the deadline in milliseconds, or 0 when none was configured} */
    public long timeoutMillis() {
        return timeout == null ? 0L : timeout.toMillis();
    }
}
