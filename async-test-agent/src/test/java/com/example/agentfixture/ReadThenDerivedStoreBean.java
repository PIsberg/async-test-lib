package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real lost update (#1187's positive control): the stored value is built from the value the read
 * returned, so two threads of a round can each overwrite the other's increment.
 */
public class ReadThenDerivedStoreBean {

    private final AtomicInteger counter = new AtomicInteger();

    public void increment() {
        int v = counter.get();
        counter.set(v + 1);
    }
}
