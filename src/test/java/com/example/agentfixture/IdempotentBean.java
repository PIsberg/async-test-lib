package com.example.agentfixture;

/**
 * Plain bean fixture for {@code SelfAttachTest}'s idempotency check. Loaded after attach
 * and used to prove a second {@code selfAttach()} does not double-weave: one setter call
 * must yield exactly one telemetry event.
 */
public class IdempotentBean {

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
