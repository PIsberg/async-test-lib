package com.example.agentfixture;

import java.util.HashMap;

/**
 * A map subclass that delegates to {@code super} on every operation.
 *
 * <p>The shape that makes naive call substitution fatal: {@code super.get(key)} is an
 * {@code INVOKESPECIAL}, and rewriting it into a static that calls {@code receiver.get(key)} would
 * dispatch virtually, arrive back in this override, and recurse until the stack ran out. Real code
 * is full of this - every decorator that extends the interface it decorates does it - so the
 * weaver must leave super calls alone.
 */
public class SuperCallingMapBean extends HashMap<String, String> {

    private static final long serialVersionUID = 1L;

    @Override
    public String get(Object key) {
        return super.get(key);
    }

    @Override
    public String put(String key, String value) {
        return super.put(key, value);
    }
}
