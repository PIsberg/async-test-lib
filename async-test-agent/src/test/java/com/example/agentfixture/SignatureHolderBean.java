package com.example.agentfixture;

/**
 * Loaded before the attach, and names {@link LoadedDuringAttachBean} in a field signature.
 *
 * <p>The pairing is the whole point. Byte Buddy describes an already-loaded class through the
 * reflection API, which eagerly resolves every field and method signature type, so describing
 * this class during the retransformation pass is what loads its partner - on the attaching
 * thread, while the circularity lock is held, where the transformer is never consulted. Loading
 * this class does not resolve the field's type: the JVM resolves a field descriptor on first
 * use, and nothing here uses it.
 */
public class SignatureHolderBean {

    private LoadedDuringAttachBean partner;

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
