package com.example.agentfixture;

import java.util.List;

/** A for-each over a list, the shape every woven Iterator.hasNext and next call comes from (#1309). */
public final class IteratorWalk {

    private IteratorWalk() {
    }

    /**
     * {@return the next element of {@code values}, or -1 when there is none}: one woven hasNext
     * and next on an iterator the caller may share
     *
     * @param values the iterator to step
     */
    public static int takeOne(java.util.Iterator<Integer> values) {
        return values.hasNext() ? values.next() : -1;
    }

    /**
     * {@return the sum of {@code values}, walked with a for-each}
     *
     * @param values the list to walk, with an iterator of its own
     */
    public static long sum(List<Integer> values) {
        long total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
