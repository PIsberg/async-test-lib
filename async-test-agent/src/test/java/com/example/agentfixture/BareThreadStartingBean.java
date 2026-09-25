package com.example.agentfixture;

/**
 * Starts a bare thread without setDaemon(true), the shape every forgot-daemon bug uses.
 */
public class BareThreadStartingBean {

    /**
     * Starts a bare thread and returns it.
     *
     * @param task the runnable to execute
     * @return the started thread
     */
    public Thread startBare(Runnable task) {
        Thread thread = new Thread(task);
        thread.start();
        return thread;
    }
}
