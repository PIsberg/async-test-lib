package com.example.agentfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * Calls {@code Map.put} through an interface-typed and a concrete-typed receiver.
 *
 * <p>Lives outside {@code se.deversity.asynctest} on purpose: the substitution wrapper refuses to
 * weave anything under the library root, so a sample inside the test's own package would silently
 * measure nothing, which is exactly what {@code CollectionAccessWeaverTest} once did.
 */
public class CollectionCallSample {

    public final Map<String, String> viaInterface = new HashMap<>();
    public final HashMap<String, String> viaConcreteType = new HashMap<>();

    /** One store through each call shape. */
    public void store(String key, String value) {
        viaInterface.put(key, value);
        viaConcreteType.put(key, value);
    }
}
