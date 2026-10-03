package com.example.agentfixture;

/**
 * The twin of {@link SwappedLockBean}: a non-final instance lock that is never reassigned, one per
 * instance. Several instances each holding their own monitor record, without the owner, exactly what
 * one reassigned field records, which is why the three-argument recording cannot decide it (#793).
 */
public class PerInstanceLockBean {

    @SuppressWarnings("FieldMayBeFinal") // non-final on purpose: the shape the detector must decide
    private Object lock = new Object();

    private int count;

    /** Increments under this instance's monitor. */
    public void increment() {
        synchronized (lock) {
            count++;
        }
    }
}
