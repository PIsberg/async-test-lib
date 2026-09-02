package com.example.gcfixture;

import se.deversity.asynctest.AgentGcHooks;

/**
 * Calls the GC hook from outside the library's package.
 *
 * <p>The hook names its caller by walking the stack past every {@code se.deversity.asynctest}
 * frame, so a test in that package would be skipped too and the report would name a JUnit frame.
 * This class stands where user code stands.
 */
public final class GcCaller {

    private GcCaller() {
    }

    /** Requests a collection the way user code does, through the substituted call. */
    public static void collect() {
        AgentGcHooks.gc();
    }
}
