package com.example.agentfixture;

import com.example.agentfixture.hidden.VisibleSub;

/** Writes static fields through their declaring class and reads them through a subclass (#1362). */
public class InheritedStaticBean {

    /** Writes {@code data}, then publishes it with the volatile {@code ready}, both through the base. */
    public void publish(int value) {
        InheritedStaticBase.data = value;
        InheritedStaticBase.ready = true;
    }

    /** Reads {@code ready} through the subclass and, when it is set, updates {@code data}. */
    public boolean bumpDataAfterReady() {
        if (InheritedStaticSub.ready) {
            InheritedStaticBase.data = InheritedStaticBase.data + 1;
            return true;
        }
        return false;
    }

    /** Writes the unguarded field through the base. */
    public void writeUnguarded(int value) {
        InheritedStaticBase.unguarded = value;
    }

    /** Updates the unguarded field through the subclass, ordered by nothing. */
    public void bumpUnguardedThroughSubclass() {
        InheritedStaticSub.unguarded = InheritedStaticSub.unguarded + 1;
    }

    /** Writes and reads a static volatile declared on a class this package cannot name. */
    public int touchHiddenVolatile(int value) {
        VisibleSub.flag = value;
        return VisibleSub.flag;
    }
}
