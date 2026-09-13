package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Reaches woven JDK calls only through method references.
 *
 * <p>A method reference compiles to an {@code invokedynamic} whose bootstrap argument is a handle
 * to the target method, and the JVM makes the call from a hidden class it spins at link time. No
 * {@code invokevirtual} to {@code append} or {@code lock} exists in this class for the weaver to
 * substitute, so until #550 all three calls below were invisible.
 */
public class SharedThroughMethodReferenceBean {

    private final StringBuilder builder = new StringBuilder();

    /** How many calls the race broke. */
    private final AtomicInteger racesAbsorbed = new AtomicInteger();

    /** Appends to the shared builder through a bound reference, {@code builder::append}. */
    public void appendThroughBoundReference() {
        try {
            Consumer<String> sink = builder::append;
            sink.accept("x");
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** Appends to the shared builder through an unbound reference, {@code StringBuilder::append}. */
    public void appendThroughUnboundReference() {
        try {
            BiFunction<StringBuilder, String, StringBuilder> append = StringBuilder::append;
            append.apply(builder, "y");
        } catch (RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** Takes a fresh lock through {@code lock::lock} and never releases it. */
    public void leakALockThroughAReference() {
        ReentrantLock lock = new ReentrantLock();
        Runnable take = lock::lock;
        take.run();
    }

    /** {@return how many calls the race broke} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }
}
