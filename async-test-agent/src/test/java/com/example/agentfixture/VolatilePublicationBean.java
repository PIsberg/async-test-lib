package com.example.agentfixture;

/**
 * Fixture for what a volatile read orders once it is woven: correct publication through a
 * volatile field, and the reads that look like it and are not.
 *
 * <p>Every method is a plain method body, so the weaver sees the field instructions themselves.
 * {@code data} and {@link Node#value} are the plain fields a detector judges; {@code ready},
 * {@code other} and {@link Node#next} are the volatile fields that do or do not order them.
 */
public class VolatilePublicationBean {

    /** A node of a linked structure, published through its predecessor's volatile {@code next}. */
    public static final class Node {

        int value;

        volatile Node next;
    }

    /** The head of the linked structure; its {@code next} is the volatile publication point. */
    private final Node head = new Node();

    /** The unpublished twin: a node reached through a plain field. */
    private Node plainHead;

    /** Volatile fields of other objects, read to fill a thread's recent reads (#805). */
    private final Node[] spare = new Node[12];

    private int data;

    private volatile boolean ready;

    private volatile boolean other;

    /** A wide volatile, whose value takes two stack slots. */
    private volatile long wideStamp;

    /** A static volatile, whose hooks name the declaring class as the owner. */
    private static volatile boolean staticReady;

    public VolatilePublicationBean() {
        for (int i = 0; i < spare.length; i++) {
            spare[i] = new Node();
        }
    }

    /** Writes a fresh node's value, then publishes the node with a volatile write (#804). */
    public void link(int value) {
        Node node = new Node();
        node.value = value;
        head.next = node;
    }

    /** Reads the node published through the volatile {@code head.next} and updates it. */
    public boolean updateLinked() {
        Node node = head.next;
        if (node == null) {
            return false;
        }
        node.value = node.value + 1;
        return true;
    }

    /** The twin of {@link #link}: the same node, stored through a plain field. */
    public void linkPlain(int value) {
        Node node = new Node();
        node.value = value;
        plainHead = node;
    }

    /** The twin of {@link #updateLinked}: the node is reached without a volatile read. */
    public boolean updatePlain() {
        Node node = plainHead;
        if (node == null) {
            return false;
        }
        node.value = node.value + 1;
        return true;
    }

    /** Writes {@code data}, then publishes it with a volatile write of {@code ready}. */
    public void publish(int value) {
        data = value;
        ready = true;
    }

    /**
     * Reads {@code ready}, runs {@code between}, then updates {@code data} whatever {@code ready}
     * said.
     *
     * <p>{@code between} lets a test land the writer's publication after the volatile read and
     * before the update, so the read returned the older value and orders nothing (#742).
     */
    public boolean bumpDataAfterReady(Runnable between) {
        boolean seen = ready;
        between.run();
        data = data + 1;
        return seen;
    }

    /** Reads {@code ready}, then the {@code next} of every spare node, then updates {@code data}. */
    public boolean bumpDataAfterManyVolatileReads() {
        // Read before ready, so no access to this object comes between ready and the reads below.
        Node[] nodes = spare;
        boolean seen = ready;
        int linked = 0;
        for (Node node : nodes) {
            if (node.next != null) {
                linked++;
            }
        }
        data = data + linked + 1;
        return seen;
    }

    /** Reads {@code ready} and nothing else, as a method of its own. */
    public boolean peekReady() {
        return ready;
    }

    /** Reads {@code other}, which nothing writes, then updates {@code data}, in a later method. */
    public boolean bumpDataAfterOther() {
        boolean seen = other;
        data = data + 1;
        return seen;
    }

    /** {@return {@code data}, for the test thread, which is not a worker and is not recorded} */
    public int observedData() {
        return data;
    }

    /** Writes {@code data}, then publishes it with a volatile write of the {@code long} {@code wideStamp}. */
    public void publishWide(int value) {
        data = value;
        wideStamp = 7L;
    }

    /** Reads {@code wideStamp}, runs {@code between}, then updates {@code data}; returns what it read. */
    public long bumpDataAfterWide(Runnable between) {
        long seen = wideStamp;
        between.run();
        data = data + 1;
        return seen;
    }

    /** Writes {@code data}, then publishes it with a write of the static volatile {@code staticReady}. */
    public void publishStatic(int value) {
        data = value;
        staticReady = true;
    }

    /** Reads the static {@code staticReady}, then updates {@code data}; returns what it read. */
    public boolean bumpDataAfterStaticReady() {
        boolean seen = staticReady;
        data = data + 1;
        return seen;
    }

    /** Spins until {@code ready} reads true, then updates {@code data}. */
    public void bumpDataAfterSpinningOnReady() {
        while (!ready) {
            Thread.onSpinWait();
        }
        data = data + 1;
    }

    /** The object double-checked locking hands out. */
    public static final class Lazy {

        int value;
    }

    /** The double-checked field: volatile, written once under this object's monitor. */
    private volatile Lazy lazy;

    /**
     * Double-checked locking with a volatile field: the thread that builds the object sets its
     * value under the lock and publishes it; every other thread reads it without the lock, after
     * the volatile read that returned it.
     */
    public int lazyValue() {
        Lazy seen = lazy;
        if (seen == null) {
            synchronized (this) {
                seen = lazy;
                if (seen == null) {
                    seen = new Lazy();
                    seen.value = 42;
                    lazy = seen;
                }
            }
        }
        return seen.value;
    }
}
