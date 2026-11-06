package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two stores in one method, the first not built from a read and the second built from one, for
 * {@code AtomicStoreVerdictsFollowWovenBytesTest} (#1191). That test rewrites these bytes before
 * weaving them, as an earlier transformer would.
 */
public class ResetThenIncrementBean {

    private final AtomicInteger gauge = new AtomicInteger();

    public void resetThenIncrement() {
        gauge.set(0);
        gauge.set(gauge.get() + 1);
    }
}
