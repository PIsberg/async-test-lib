package com.example.agentfixture;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixture for {@code ABAProblemDetector} fed by the agent (#817): the head of a lock-free stack,
 * read, stored and swapped through an {@code AtomicReference} whose every call site is in this
 * class, so the weaver substitutes each one.
 */
public class AbaStackBean {

    /** A stack node; its mutable {@code next} is what an A-B-A of the head leaves stale. */
    public static final class Node {

        Node next;
        private final String name;

        public Node(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final AtomicReference<Node> head;

    public AbaStackBean(Node initial) {
        head = new AtomicReference<>(initial);
    }

    /** {@return the head, read the way a pop reads the value its compare-and-set expects} */
    public Node read() {
        return head.get();
    }

    /** Stores {@code node} as the head. */
    public void store(Node node) {
        head.set(node);
    }

    /** {@return whether the head was {@code expected} and is now {@code update}} */
    public boolean swap(Node expected, Node update) {
        return head.compareAndSet(expected, update);
    }
}
