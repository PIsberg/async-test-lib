package se.deversity.asynctest.example.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * A stand-in for {@code List.ofLazy(size, fn)}, with the property that matters: each element is
 * computed at most once, on whichever thread asks for it first, and every other caller for that
 * element waits for that computation rather than starting its own.
 *
 * <p>That per-element independence is what makes a lazy collection different from a single
 * {@code LazyConstant}. Elements can be computing at the same time, so a mapping function that
 * reads its own collection couples them - and if the coupling runs both ways, two threads each
 * hold one element and wait for the other. The JDK breaks that cycle when it can see it on one
 * thread; spread across two, there is nothing to see.
 */
public final class LazyGrid {

    private final Map<Integer, Object> cells = new ConcurrentHashMap<>();
    private final IntFunction<String> mapper;

    /**
     * @param mapper the mapping function, run at most once per index
     */
    public LazyGrid(IntFunction<String> mapper) {
        this.mapper = mapper;
    }

    /**
     * {@return element {@code index}, computing it if this is the first request}
     *
     * @param index the element's index
     */
    public String get(int index) {
        return (String) cells.computeIfAbsent(index, i -> mapper.apply(i));
    }
}
