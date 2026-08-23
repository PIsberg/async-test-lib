package com.example.agentfixture;

import java.util.Map;

/**
 * A record with a two-slot primitive component, the exact shape that broke substitution weaving.
 *
 * <p>Every record's {@code equals}/{@code hashCode}/{@code toString} is an {@code invokedynamic}
 * whose bootstrap arguments include one field method handle per component; a primitive component
 * gives that handle a one-character descriptor ({@code J} here), and parsing it as a method
 * descriptor is what threw. Top-level rather than nested so a child-first class loader can link
 * its {@code toString} without reaching for an outer class in another loader.
 */
public record MeasuredSpanRecord(long nanos, String label) {

    /** A collection call inside a record body, so substitution itself is also exercised. */
    public boolean noteInto(Map<Object, Object> sink) {
        sink.put(label, nanos);
        return sink.containsKey(label);
    }
}
