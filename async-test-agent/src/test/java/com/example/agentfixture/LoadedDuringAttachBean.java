package com.example.agentfixture;

/**
 * Never loaded before the attach: it is only a field-signature type of
 * {@link SignatureHolderBean}, and the attach itself is what loads it.
 *
 * <p>That makes it the class in the window issue #321 named. It is in neither set the two
 * weaving paths cover: the retransformation pass took its snapshot of the loaded classes
 * before this one existed, and load-time weaving skipped it because the transformer declines
 * every class that loads on the attaching thread while the install holds the circularity lock,
 * without calling a listener. A discovery strategy that re-queries the loaded set is what
 * closes it.
 */
public class LoadedDuringAttachBean {

    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
