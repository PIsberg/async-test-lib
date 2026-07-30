package com.example.agentfixture;

/**
 * Plain bean fixture for {@code SelfAttachTest}, deliberately placed in a
 * non-ignored package ({@code com.example.*}) so the agent's default ignore matcher does
 * not skip it. It is loaded <em>before</em> the agent self-attaches, so its accessors can
 * only be instrumented via the retransformation path.
 */
public class BeforeAttachBean {

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
