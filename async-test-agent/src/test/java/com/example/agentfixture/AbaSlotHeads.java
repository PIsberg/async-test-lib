package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The head of {@link AbaStackBean}'s stack held in the three reference slots that are not an
 * {@code AtomicReference} (#817): a field reached through an {@code AtomicReferenceFieldUpdater},
 * an {@code AtomicReferenceArray} element and a field reached through a {@code VarHandle}. Every
 * call site is here, so the weaver substitutes each one.
 */
public final class AbaSlotHeads {

    private AbaSlotHeads() {
    }

    /** One head: read, stored and swapped through the slot its implementation names. */
    public interface Head {
        AbaStackBean.Node read();

        void store(AbaStackBean.Node node);

        boolean swap(AbaStackBean.Node expected, AbaStackBean.Node update);
    }

    /** {@return the three heads, each holding {@code initial}} */
    public static Head[] all(AbaStackBean.Node initial) {
        return new Head[] {new ThroughUpdater(initial), new ThroughArray(initial), new ThroughHandle(initial)};
    }

    /** A volatile field swapped through an {@code AtomicReferenceFieldUpdater}. */
    public static final class ThroughUpdater implements Head {
        private static final AtomicReferenceFieldUpdater<ThroughUpdater, AbaStackBean.Node> HEAD =
                AtomicReferenceFieldUpdater.newUpdater(ThroughUpdater.class, AbaStackBean.Node.class, "head");

        private volatile AbaStackBean.Node head;

        ThroughUpdater(AbaStackBean.Node initial) {
            HEAD.set(this, initial);
        }

        @Override public AbaStackBean.Node read() { return HEAD.get(this); }
        @Override public void store(AbaStackBean.Node node) { HEAD.set(this, node); }
        @Override public boolean swap(AbaStackBean.Node expected, AbaStackBean.Node update) {
            return HEAD.compareAndSet(this, expected, update);
        }
        @Override public String toString() { return "updater"; }
    }

    /** Element 0 of an {@code AtomicReferenceArray}. */
    public static final class ThroughArray implements Head {
        private final AtomicReferenceArray<AbaStackBean.Node> heads = new AtomicReferenceArray<>(1);

        ThroughArray(AbaStackBean.Node initial) {
            heads.set(0, initial);
        }

        @Override public AbaStackBean.Node read() { return heads.get(0); }
        @Override public void store(AbaStackBean.Node node) { heads.set(0, node); }
        @Override public boolean swap(AbaStackBean.Node expected, AbaStackBean.Node update) {
            return heads.compareAndSet(0, expected, update);
        }
        @Override public String toString() { return "AtomicReferenceArray"; }
    }

    /** A volatile field read, stored and swapped through a {@code VarHandle}. */
    public static final class ThroughHandle implements Head {
        private static final VarHandle HEAD;

        static {
            try {
                HEAD = MethodHandles.lookup().findVarHandle(ThroughHandle.class, "head", AbaStackBean.Node.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile AbaStackBean.Node head;

        ThroughHandle(AbaStackBean.Node initial) {
            HEAD.setVolatile(this, initial);
        }

        @Override public AbaStackBean.Node read() { return (AbaStackBean.Node) HEAD.getVolatile(this); }
        @Override public void store(AbaStackBean.Node node) { HEAD.setVolatile(this, node); }
        @Override public boolean swap(AbaStackBean.Node expected, AbaStackBean.Node update) {
            return HEAD.compareAndSet(this, expected, update);
        }
        @Override public String toString() { return "VarHandle"; }
    }
}
