package com.example.agentfixture;

import com.example.agentfixture.hidden.OtherVisibleSub;
import com.example.agentfixture.hidden.VisibleSub;

/**
 * Plain data published through one static volatile that two call sites name through two different
 * public subclasses of its package-private declaring class (#1396).
 */
public class HiddenFlagPublication {

    /** The value the writer stores in the flag, which no other fixture uses. */
    private static final int READY = 1396;

    public int payload;

    /** Updates the payload, then sets the flag through {@code VisibleSub}. */
    public void publish() {
        payload = payload + 1;
        VisibleSub.flag = READY;
    }

    /** Reads the flag through {@code OtherVisibleSub}, and only when it was set updates the payload. */
    public int bumpAfterFlag() {
        if (OtherVisibleSub.flag != READY) {
            return -1;
        }
        payload = payload + 1;
        return payload;
    }

    /** Updates the payload having read no flag: nothing orders it after the writer. */
    public int bumpWithoutFlag() {
        payload = payload + 1;
        return payload;
    }
}
