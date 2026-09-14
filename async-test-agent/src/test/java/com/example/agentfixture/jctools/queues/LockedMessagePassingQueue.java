package com.example.agentfixture.jctools.queues;

/**
 * The simplest correct implementation: a fixed array under the queue's own monitor.
 *
 * <p>A plain array rather than a JDK collection on purpose: a {@code java.util.Queue} inside would
 * report the take on its own, and the test could no longer tell whether the weaver recognised
 * the {@code MessagePassingQueue} call site.
 */
public final class LockedMessagePassingQueue<T> implements MessagePassingQueue<T> {

    private final Object[] elements = new Object[8];
    private int size;

    @Override
    public synchronized boolean relaxedOffer(T element) {
        if (size == elements.length) {
            return false;
        }
        elements[size] = element;
        size++;
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized T relaxedPoll() {
        if (size == 0) {
            return null;
        }
        size--;
        T head = (T) elements[size];
        elements[size] = null;
        return head;
    }
}
