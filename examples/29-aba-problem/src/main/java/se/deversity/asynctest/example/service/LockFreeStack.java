package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A lock-free stack implemented with a linked list and CAS operations.
 *
 * BUG: The ABA problem. The {@code pop()} operation reads the current head,
 * computes the new head (head.next), and then performs:
 *
 *   CAS(head, observed, observed.next)
 *
 * Between the read and the CAS, another thread can:
 *   1. Pop the current head (A)
 *   2. Pop the next node (B)
 *   3. Push A back (A is now back on top but its {@code next} pointer
 *      may point to freed/recycled memory)
 *
 * The original thread's CAS sees A (as expected) and succeeds — but
 * {@code observed.next} now points to a stale or recycled node,
 * corrupting the stack.
 *
 * FIX: Use {@link java.util.concurrent.atomic.AtomicStampedReference} to
 * pair each node reference with a monotonically increasing version stamp.
 * The CAS then compares both the reference and the stamp, so a
 * push-after-pop cycle produces a different stamp and the CAS correctly fails.
 */
public class LockFreeStack<T> {

    private static class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

    /**
     * Push a value onto the stack.
     */
    public void push(T value) {
        Node<T> newNode = new Node<>(value);
        Node<T> currentHead;
        do {
            currentHead = head.get();
            newNode.next = currentHead;
        } while (!head.compareAndSet(currentHead, newNode));
    }

    /**
     * Pop the top value from the stack, or return null if empty.
     *
     * VULNERABLE to ABA: between reading {@code observed} and executing CAS,
     * another thread may have popped {@code observed}, pushed something else,
     * then pushed {@code observed} back. The CAS will succeed even though
     * the stack structure has changed underneath.
     */
    public T pop() {
        Node<T> observed;
        Node<T> next;
        do {
            observed = head.get();
            if (observed == null) {
                return null;
            }
            next = observed.next;
            // ABA window: another thread can modify head between this read and the CAS
        } while (!head.compareAndSet(observed, next));
        return observed.value;
    }

    public boolean isEmpty() {
        return head.get() == null;
    }

    /**
     * Expose the raw head reference for detector instrumentation in tests.
     */
    public AtomicReference<Node<T>> headRef() {
        return head;
    }
}
