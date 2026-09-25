package com.example.agentfixture;

/**
 * Starts a thread with an explicit setDaemon(true) call.
 */
public class ExplicitDaemonThreadStartingBean {

    /**
     * Starts an explicit daemon thread and returns it.
     *
     * @param task the runnable to execute
     * @return the started thread
     */
    public Thread startDaemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
