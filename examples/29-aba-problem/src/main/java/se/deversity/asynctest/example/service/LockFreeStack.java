package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * A lock-free stack implemented with a linked list, CAS operations and a node free list.
 *
 * <p>BUG: the ABA problem. {@code pop()} reads the current head, computes the new head
 * ({@code observed.next}) and then performs {@code CAS(head, observed, observed.next)}.
 * Between the read and the CAS another thread can pop {@code observed}, pop the node under
 * it, and push {@code observed} back. The first thread's CAS sees the reference it expected
 * and succeeds, while {@code observed.next} now points somewhere else entirely.
 *
 * <p><strong>The free list is what makes that reachable.</strong> A stack that allocates
 * {@code new Node<>(value)} on every push cannot produce ABA in Java: a popped node is
 * garbage and the next push gets a reference that has never been head before, so the stale
 * CAS fails and retries, which is correct. ABA needs the same node to come back, and the
 * usual reason it comes back is an allocation-avoidance pool exactly like the one below.
 * That is why this class has one, and it is the difference between a demonstration and a
 * story about C.
 *
 * <p>FIX: pair the head with a monotonic stamp, using
 * {@link java.util.concurrent.atomic.AtomicStampedReference}. An A to B to A cycle bumps the
 * stamp twice, so a CAS carrying the old stamp fails even though the reference matches.
 * Or stop recycling nodes and let the garbage collector do what it is for.
 *
 * <p>INSTRUMENTATION: ABAProblemDetector is recording-fed and reasons about a history of
 * values, so it has to be told what the head was and what it became, at each successful CAS.
 * The two hooks below are plain BiConsumers that default to no-ops, so the production path
 * never touches the test library. This is the seam, not the bug.
 *
 * @param <T> the element type
 */
public class LockFreeStack<T> {

    private static class Node<T> {
        T value;
        Node<T> next;
    }

    private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

    /** BUG: popped nodes come back here and are handed out again, which is what enables ABA. */
    private final ConcurrentLinkedQueue<Node<T>> freeList = new ConcurrentLinkedQueue<>();

    private volatile BiConsumer<Object, Object> onHeadChange = (from, to) -> { };

    private volatile BiConsumer<Object, Object> onPopCas = (expected, updated) -> { };

    /**
     * Push a value onto the stack, reusing a recycled node when one is available.
     *
     * @param value the value to push
     */
    public void push(T value) {
        Node<T> node = freeList.poll();
        if (node == null) {
            node = new Node<>();
        }
        node.value = value;

        Node<T> currentHead;
        do {
            currentHead = head.get();
            node.next = currentHead;
        } while (!head.compareAndSet(currentHead, node));

        onHeadChange.accept(currentHead, node);
    }

    /**
     * Pop the top value from the stack.
     *
     * <p>VULNERABLE to ABA: between reading {@code observed} and executing the CAS, another
     * thread may have popped {@code observed}, pushed something else, then pushed
     * {@code observed} back off the free list. The CAS succeeds even though the stack
     * underneath it has changed.
     *
     * @return the value that was on top, or null if the stack was empty
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
            // ABA window: another thread can swing head away and back between here and the CAS.
        } while (!head.compareAndSet(observed, next));

        T value = observed.value;
        onHeadChange.accept(observed, next);
        onPopCas.accept(observed, next);

        observed.value = null;
        observed.next = null;
        freeList.offer(observed);   // BUG: back into circulation, reference and all
        return value;
    }

    /**
     * {@return whether the stack is currently empty}
     */
    public boolean isEmpty() {
        return head.get() == null;
    }

    /**
     * Installs the hooks ABAProblemDetector needs. No-ops by default.
     *
     * @param onChange called after every successful head CAS, with the old and new head
     * @param onPop    called after a pop's CAS succeeds, with the reference it expected and wrote
     */
    public void observeHead(BiConsumer<Object, Object> onChange, BiConsumer<Object, Object> onPop) {
        this.onHeadChange = onChange;
        this.onPopCas = onPop;
    }
}
