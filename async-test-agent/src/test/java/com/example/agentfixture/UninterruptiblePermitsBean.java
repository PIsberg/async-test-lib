package com.example.agentfixture;

import java.util.concurrent.Semaphore;

/**
 * Takes permits through the two Semaphore calls the agent did not weave, then returns them: an
 * uninterruptible acquire, and a drain whose permits go back with one release (#1501). Each method
 * first uses one permit through the woven acquire, so the thread has acquired before.
 */
public class UninterruptiblePermitsBean {

    private final Semaphore permits = new Semaphore(4);

    public void usePermitUninterruptibly() throws InterruptedException {
        permits.acquire();
        permits.release();
        permits.acquireUninterruptibly();
        try {
            Thread.onSpinWait(); // the work
        } finally {
            permits.release();
        }
    }

    public void drainAndReturn() throws InterruptedException {
        permits.acquire();
        permits.release();
        int drained = permits.drainPermits();
        try {
            Thread.onSpinWait(); // exclusive work while every permit is held
        } finally {
            permits.release(drained);
        }
    }
}
