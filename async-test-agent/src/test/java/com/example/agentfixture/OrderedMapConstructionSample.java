package com.example.agentfixture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every shape in which a class builds a {@code LinkedHashMap} with the three-argument constructor,
 * for the order capture (#807) to be checked against: each must verify once woven, and each map
 * must be reported once, with the order it was built in.
 */
public class OrderedMapConstructionSample {

    /** {@return a map in access order, from a constant} */
    public static Map<String, String> accessOrdered() {
        return new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * {@return a map in the order given, from a value}
     *
     * @param accessOrder whether the map is in access order
     */
    public static Map<String, String> ordered(boolean accessOrder) {
        return new LinkedHashMap<>(16, 0.75f, accessOrder);
    }

    /** Builds a map and drops it, which javac compiles to {@code NEW, DUP, <init>, POP}. */
    public static void discarded() {
        new LinkedHashMap<String, String>(16, 0.75f, true);
    }

    /**
     * {@return an insertion-ordered map whose capacity comes from an access-ordered one built in its
     * arguments, so two constructor calls nest}
     */
    public static Map<String, String> nested() {
        return new LinkedHashMap<>(new LinkedHashMap<String, String>(4, 0.75f, true).size() + 16,
                0.75f, false);
    }

    /** {@return two maps, the first from a constructor that cannot set the order} */
    public static List<Map<String, String>> besideAnUnorderedOne() {
        return List.of(new LinkedHashMap<>(8), new LinkedHashMap<>(16, 0.75f, true));
    }

    /**
     * {@return a map whose order argument has a branch in it}
     *
     * @param first  one condition
     * @param second the other
     */
    public static Map<String, String> withABranchInItsOrder(boolean first, boolean second) {
        return new LinkedHashMap<>(16, 0.75f, first && second);
    }

    /** A direct subclass setting its order through its superclass call, as an LRU cache does. */
    public static final class LruSample extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        /**
         * @param accessOrder the order handed to the superclass constructor
         */
        public LruSample(boolean accessOrder) {
            super(16, 0.75f, accessOrder);
        }

        /** Delegates, so only the constructor it delegates to reports. */
        public LruSample() {
            this(true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 8;
        }
    }

    /** A subclass whose superclass call builds another ordered map in its arguments. */
    public static final class SizedFromAnother extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        /** Builds an insertion-ordered map sized by an access-ordered one. */
        public SizedFromAnother() {
            super(new LinkedHashMap<String, String>(2, 0.75f, true).size() + 16, 0.75f, false);
        }
    }
}
