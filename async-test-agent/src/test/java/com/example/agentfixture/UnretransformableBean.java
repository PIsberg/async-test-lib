package com.example.agentfixture;

/**
 * Stands in for a class the JVM refuses to re-weave.
 *
 * <p>Nothing about this class is special; the refusal is injected by the test's
 * {@code Instrumentation} wrapper, because the real refusals are not reproducible on demand. The
 * one that started it was Netty's {@code Log4JLogger}, which loads fine and then fails
 * retransformation with {@code InternalError: class redefinition failed: invalid class} because
 * re-verifying it needs a log4j class that is not on the classpath. What matters for the
 * regression is only that one class in the batch fails, not why.
 */
public class UnretransformableBean {

    private int value;

    /** {@return the current value} */
    public int getValue() {
        return value;
    }

    /**
     * Sets the value.
     *
     * @param value the new value
     */
    public void setValue(int value) {
        this.value = value;
    }
}
