package com.example.agentfixture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;

/**
 * Queue offers and takes inside {@code synchronized} methods, an instance and a static one, and
 * outside one (#796).
 *
 * <p>A {@code synchronized} method takes its monitor from the access flag, with no instruction the
 * weaver could rewrite, so the only way a queue hook learns of it is the weaver passing it. Lives
 * outside {@code se.deversity.asynctest} for the reason {@link CollectionCallSample} gives.
 */
public class SynchronizedQueueCallSample {

    public final Queue<Object> queue = new ArrayDeque<>();

    public static final Deque<Object> STATIC_DEQUE = new ArrayDeque<>();

    /** An offer and a poll holding this object's monitor. @param element what to pass through */
    public synchronized Object offerAndPollHoldingThis(Object element) {
        queue.offer(element);
        return queue.poll();
    }

    /** A push and a pop holding the class's monitor. @param element what to pass through */
    public static synchronized Object pushAndPopHoldingTheClass(Object element) {
        STATIC_DEQUE.push(element);
        return STATIC_DEQUE.pop();
    }

    /** An offer and a poll holding nothing. @param element what to pass through */
    public Object offerAndPollHoldingNothing(Object element) {
        queue.offer(element);
        return queue.poll();
    }
}
