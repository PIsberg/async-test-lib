package com.example.agentfixture;

/**
 * Bean fixture whose fields are touched inside method bodies, one under a lock and one not.
 *
 * <p>The pair is the point. {@code fields=true} makes both mutations visible to the agent, and
 * before monitor weaving they looked identical to it: a {@code count++} is a {@code GETFIELD} and
 * a {@code PUTFIELD} whether or not a monitor is held, and {@code synchronized} emits no callback
 * of its own. So the agent-fed detectors reported the guarded field exactly as loudly as the
 * racing one, which is what {@code FieldWeavingEndToEndTest} now pins as fixed.
 *
 * <p>The guard is an explicit {@code synchronized (lock)} block on a private lock object, the
 * shape only monitor weaving can see: a synchronized method carries the {@code ACC_SYNCHRONIZED}
 * flag and no {@code MONITORENTER} instruction, and is covered separately by the receiver probe
 * that {@code LockModelWeavingEndToEndTest} pins with {@code SynchronizedMethodBean}.
 */
public class GuardedFieldMutationBean {

    /** A private lock object: the shape a detector cannot name without being told. */
    private final Object lock = new Object();

    private int guarded;
    private int unguarded;

    /** Reads and writes {@code guarded} directly, with the monitor held for both. */
    public void incrementGuarded() {
        synchronized (lock) {
            guarded = guarded + 1;
        }
    }

    /** Reads and writes {@code unguarded} directly, with nothing held. The control. */
    public void incrementUnguarded() {
        unguarded = unguarded + 1;
    }

    /** Lets a test observe the outcome without touching the fields under test. */
    public int observedGuarded() {
        synchronized (lock) {
            return guarded;
        }
    }
}
