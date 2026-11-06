package com.example.agentfixture;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    private int lastSeen;

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

    /** The read goes into a helper that only notes it and returns an unrelated size (#1190). */
    public void readPassedToAHelperThatIgnoresIt() {
        gauge.set(sizeAfterNoting(gauge.get()));
    }

    private int sizeAfterNoting(int seen) {
        lastSeen = seen;
        return queue.size();
    }

    /** The read goes into a helper whose result is built from it: still the lost update (#1190). */
    public void readPassedToAHelperThatBuildsOnIt() {
        gauge.set(plusOne(gauge.get()));
    }

    private static int plusOne(int v) {
        return v + 1;
    }

    /** The read happens inside a helper whose result is stored: derived although no read is local. */
    public void readInsideAHelper() {
        gauge.set(current() + 1);
    }

    private int current() {
        return gauge.get();
    }

    /** The read goes into a method a subclass may override, so its bytes here prove nothing. */
    public void readPassedToAnOverridableMethod() {
        gauge.set(overridable(gauge.get()));
    }

    public int overridable(int seen) {
        return queue.size();
    }

    /** The read goes into a recursive helper that never returns it: the summary must still settle. */
    public void readPassedToARecursiveHelper() {
        gauge.set(countDown(gauge.get(), 3));
    }

    private static int countDown(int seen, int n) {
        return n == 0 ? 0 : countDown(seen, n - 1);
    }
}
