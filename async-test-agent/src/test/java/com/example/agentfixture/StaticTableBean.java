package com.example.agentfixture;

import java.util.ArrayList;
import java.util.List;

/**
 * A lookup table filled in a static initializer and only read afterwards.
 *
 * <p>Correct: the JVM runs the initializer once, under the class's initialization lock, and no
 * other thread reads {@code NAMES} until it has returned. The first thread of a round to call
 * {@link #name} is the one that fills the list, in the same round the others read it.
 */
public final class StaticTableBean {

    private static final List<String> NAMES = new ArrayList<>();

    static {
        for (int i = 0; i < 16; i++) {
            NAMES.add("name-" + i);
        }
    }

    private StaticTableBean() {
    }

    /** @param index a position below 16 @return the name the initializer stored there */
    public static String name(int index) {
        return NAMES.get(index);
    }
}
