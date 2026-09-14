package com.example.agentfixture.jctools.queues;

/**
 * Stands in for JCTools' {@code MessagePassingQueue}, which libraries shade under their own
 * package: the weaver recognises it by the tail of its name, so a copy under any prefix counts.
 *
 * @param <T> the element type
 */
public interface MessagePassingQueue<T> {

    /** @param element the element @return whether it was accepted */
    boolean relaxedOffer(T element);

    /** @return the head, or {@code null} when empty */
    T relaxedPoll();
}
