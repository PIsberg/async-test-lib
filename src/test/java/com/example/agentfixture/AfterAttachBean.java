package com.example.agentfixture;

/**
 * Plain bean fixture for {@code SelfAttachTest}, in a non-ignored package so the agent
 * instruments it. It is loaded (via reflection) <em>after</em> the agent self-attaches,
 * so its accessors are woven at load time.
 */
public class AfterAttachBean {

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
