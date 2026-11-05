package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One method per shape the store analysis must judge, for {@code AtomicStoreTaintAnalysisTest} (#1187).
 * None of these is run; their bytecode is what the analysis reads.
 */
public class AtomicStoreShapesBean {

    private final AtomicLong total = new AtomicLong();
    private final AtomicInteger gauge = new AtomicInteger();
    private final AtomicInteger other = new AtomicInteger();

    /** A two-word value (long) read and stored back: the built-from-the-read case at size 2. */
    public void longIncrement() {
        long v = total.get();
        total.set(v + 1L);
    }

    /** A read whose result is thrown away, then a constant store: nothing is built from the read. */
    public void constantAfterAnIgnoredRead() {
        gauge.get();
        gauge.set(0);
    }

    /** A read reaches the store on one branch, so the store is derived on some path. */
    public void readOnOneBranch(boolean reset) {
        int v = gauge.get();
        if (reset) {
            v = 0;
        }
        gauge.set(v);
    }

    /** A read of one atomic stored into another: derived, by the conservative rule. */
    public void readOfOneAtomicStoredInAnother() {
        int v = gauge.get();
        other.set(v);
    }
}
