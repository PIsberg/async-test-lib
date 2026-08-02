package com.example.agentfixture;

/**
 * Bean fixture whose field is only ever touched from inside a method body, never through an
 * accessor. This is the shape the agent cannot observe, and
 * {@code AgentFeedsDetectorEndToEndTest} pins that.
 *
 * <p>{@link #increment()} is neither a getter nor a setter by the JavaBean matchers the weaver
 * uses, so the {@code count++} inside it produces no telemetry event however many threads run it.
 * It is the single most common shape of a real race, which is why the limitation is worth a test
 * rather than only a sentence in the javadoc.
 */
public class DirectFieldMutationBean {

    private int count;

    /** Reads and writes {@code count} directly. Not an accessor, so not woven. */
    public void increment() {
        count = count + 1;
    }

    /** Lets a test observe the outcome without going through the field under test. */
    public int observedCount() {
        return count;
    }
}
