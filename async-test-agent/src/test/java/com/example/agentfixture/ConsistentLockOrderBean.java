package com.example.agentfixture;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The correctly ordered twin of {@link LockOrderInversionBean}: same two locks, one order.
 *
 * <p>This is the half that decides whether the agent's new lock feed is safe to ship. A detector
 * that fires on the inverted bean proves only that it fires; a detector that also fires here would
 * be reporting correct code, and correct code being flagged is the failure mode that gets a tool
 * switched off. Both methods take {@code gamma} before {@code delta}, always, so there is no
 * hold-and-wait cycle for any interleaving to produce.
 */
public class ConsistentLockOrderBean {

    private final ReentrantLock gamma = new ReentrantLock();
    private final ReentrantLock delta = new ReentrantLock();

    private int work;

    /** Takes {@code gamma}, then {@code delta}. */
    public void forward() {
        gamma.lock();
        try {
            if (delta.tryLock()) {
                try {
                    work++;
                } finally {
                    delta.unlock();
                }
            }
        } finally {
            gamma.unlock();
        }
    }

    /** Takes {@code gamma}, then {@code delta}, by the other call path. */
    public void alsoForward() {
        gamma.lock();
        try {
            if (delta.tryLock()) {
                try {
                    work += 2;
                } finally {
                    delta.unlock();
                }
            }
        } finally {
            gamma.unlock();
        }
    }

    /** {@return how much work ran, so the JIT cannot delete the critical sections} */
    public int work() {
        return work;
    }
}
