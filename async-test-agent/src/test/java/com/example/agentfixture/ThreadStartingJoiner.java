package com.example.agentfixture;

/**
 * Starts a thread built somewhere else and waits for it: the woven {@code Thread.start} is the
 * only sign the thread works for the run whose worker called this.
 */
public class ThreadStartingJoiner {

    /**
     * Starts {@code thread} and waits for it to end.
     *
     * @param thread a thread constructed elsewhere, not yet started
     * @throws InterruptedException if interrupted while waiting
     */
    public void startAndJoin(Thread thread) throws InterruptedException {
        thread.start();
        thread.join();
    }
}
