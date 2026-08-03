package com.example.agentfixture;

/**
 * Bean fixture for {@code AgentFeedsDetectorEndToEndTest}: one field reachable only through a
 * getter and a setter, which is the shape the agent can observe.
 *
 * <p>Its own class, rather than reusing {@code AfterAttachBean}, so that the end-to-end test's
 * findings cannot be confused with accesses made by {@code SelfAttachTest}. The field is
 * deliberately unsynchronised: the point is for a detector to say so.
 */
public class SharedCounterBean {

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
