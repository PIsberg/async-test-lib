package com.example.agentfixture;

/**
 * The most ordinary correct class in Java: every field access inside {@code synchronized} methods.
 *
 * <p>{@code ACC_SYNCHRONIZED} compiles to a flag and no instruction, so monitor weaving sees
 * nothing here; what makes this bean readable as guarded is the receiver probe and the method
 * monitor the field weaver passes with each access. Until those existed, this shape drew an
 * AtomicityValidator finding, which is how Guava's {@code FileBackedOutputStream}, a documented
 * thread-safe class, read as a race in the corpus eval.
 */
public class SynchronizedMethodBean {

    private int count;

    /** Reads and writes {@code count} with the monitor held by the method itself. */
    public synchronized void increment() {
        count = count + 1;
    }

    /** Reads under the same monitor. */
    public synchronized int current() {
        return count;
    }
}
