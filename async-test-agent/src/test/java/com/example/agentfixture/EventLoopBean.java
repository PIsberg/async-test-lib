package com.example.agentfixture;

import reactor.core.scheduler.NonBlocking;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts threads and blocks on them the way framework code does, recording nothing. The thread
 * class here stands in for a Reactor scheduler's: it carries the marker interface's real name. The
 * fixture package is in the agent's {@code includes=}, which is the attribution rule this detector
 * needs (#991): the woven {@code Thread.start} here is what tells the run the thread is its own.
 */
public class EventLoopBean {

    /** A thread of a non-blocking scheduler, such as one Schedulers.parallel() creates. */
    public static final class NonBlockingWorker extends Thread implements NonBlocking {
        public NonBlockingWorker(Runnable task) {
            super(task, "parallel-worker");
        }
    }

    /**
     * Starts a non-blocking thread that sleeps, and waits for it.
     *
     * @throws InterruptedException if interrupted while joining
     */
    public void sleepOnANonBlockingThread() throws InterruptedException {
        Thread thread = new NonBlockingWorker(EventLoopBean::nap);
        thread.start();
        thread.join();
    }

    /**
     * Starts an ordinary thread that sleeps the same way, and waits for it.
     *
     * @throws InterruptedException if interrupted while joining
     */
    public void sleepOnAnOrdinaryThread() throws InterruptedException {
        Thread thread = new Thread(EventLoopBean::nap, "ordinary-worker");
        thread.start();
        thread.join();
    }

    /**
     * Starts a non-blocking thread that keeps sleeping until {@code stop} is set: started by
     * whoever calls this, which in the twin test is no run's worker.
     *
     * @param stop set to end the thread
     * @return the started thread
     */
    public Thread keepSleepingOnANonBlockingThread(AtomicBoolean stop) {
        Thread thread = new NonBlockingWorker(() -> {
            while (!stop.get()) {
                nap();
            }
        });
        thread.start();
        return thread;
    }

    private static void nap() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
