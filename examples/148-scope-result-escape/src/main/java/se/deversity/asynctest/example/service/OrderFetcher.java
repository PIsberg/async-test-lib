package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The two shapes of "return the results of a structured scope", written against plain types so
 * the example compiles on JDK 21 while modelling the JDK 26 return contract.
 *
 * <p>JDK 25's joiners returned a {@code Stream<Subtask<T>>}. A stream is lazy and single-use, so
 * holding one past {@code close()} failed early and loudly. JDK 26 returns a {@code List}, which
 * is the ergonomic improvement everyone asked for and also a value that stores in a field without
 * complaint - which is how the subtask handles get out of the structure that guaranteed them.
 */
public final class OrderFetcher {

    /** A stand-in for {@code Subtask<String>}: valid only while its scope is open. */
    public static final class Handle {
        private final String value;
        private boolean scopeOpen = true;

        Handle(String value) { this.value = value; }

        /** Marks the owning scope closed; from here the handle is outside the structure. */
        void scopeClosed() { scopeOpen = false; }

        /** {@return whether the owning scope is still open} */
        public boolean isScopeOpen() { return scopeOpen; }

        /** {@return the subtask's result} */
        public String get() { return value; }
    }

    private final List<Handle> handles = new ArrayList<>();

    /** Forks a subtask, in the sense this example needs: it produces a handle. */
    public Handle fork(String value) {
        Handle h = new Handle(value);
        handles.add(h);
        return h;
    }

    /** {@return an unmodifiable list of handles, the way a JDK 26 joiner returns one} */
    public List<Handle> join() {
        return List.copyOf(handles);
    }

    /** Closes the scope, invalidating every handle it produced. */
    public void close() {
        for (Handle h : handles) h.scopeClosed();
    }
}
