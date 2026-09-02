package com.example.agentfixture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Every shape javac gives an {@code offer}'s boolean, and two pops that are not an offer's.
 *
 * <p>The four offer shapes compile to a different instruction after the {@code invokeinterface}:
 * {@code POP} for a discarded result, {@code IFNE} for a branch, {@code IRETURN} for a return,
 * {@code ISTORE} for a local. Exactly one of them is the caller not looking, and the weaver's
 * one-instruction lookahead must rewrite that one and nothing else (#454).
 *
 * <p>The last two methods are the failure the lookahead has to avoid rather than the one it has
 * to find. A {@code POP} after some other call must stay a {@code POP}; if the flag were stale,
 * the reference popped in {@link #unrelatedReferencePopped} would be handed to a hook declared to
 * take a boolean, which the verifier rejects when the class loads.
 */
public class OfferShapesSample {

    private final BlockingQueue<Object> queue;

    /** Where the stored-shape's result goes when it was {@code true}. */
    public int stored;

    /** The list an unrelated boolean is popped from. */
    public final List<Object> list = new ArrayList<>();

    /** The map an unrelated reference is popped from. */
    public final Map<Object, Object> map = new HashMap<>();

    public OfferShapesSample(BlockingQueue<Object> queue) {
        this.queue = queue;
    }

    /** {@code q.offer(x);} - the boolean is popped. */
    public void discarded(Object x) {
        queue.offer(x);
    }

    /** The timed form, popped. */
    public void discardedTimed(Object x) throws InterruptedException {
        queue.offer(x, 1, TimeUnit.MILLISECONDS);
    }

    /** {@code if (!q.offer(x))} - the boolean is branched on. */
    public boolean branched(Object x) {
        if (!queue.offer(x)) {
            return false;
        }
        return true;
    }

    /** {@code return q.offer(x);} - the boolean is returned. */
    public boolean returned(Object x) {
        return queue.offer(x);
    }

    /** {@code boolean added = q.offer(x);} - the boolean is stored. */
    public void storedInALocal(Object x) {
        boolean added = queue.offer(x);
        if (added) {
            stored++;
        }
    }

    /** {@code list.add(x);} - a popped boolean from a call that is not an offer. */
    public void unrelatedBooleanPopped(Object x) {
        list.add(x);
    }

    /** {@code map.put(k, v);} - a popped reference. The case a stale flag would break. */
    public void unrelatedReferencePopped(Object x) {
        map.put(x, x);
    }
}
