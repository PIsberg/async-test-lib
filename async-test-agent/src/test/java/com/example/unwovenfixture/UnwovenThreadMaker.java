package com.example.unwovenfixture;

/**
 * Constructs threads in a package the weaver never scans (#737), so any daemon decision made here
 * is invisible to the agent.
 */
public final class UnwovenThreadMaker {

    private UnwovenThreadMaker() {
    }

    /**
     * {@return an unstarted thread given setDaemon(true) here}
     *
     * @param name the thread's name
     * @param task what the thread runs
     */
    public static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * {@return an unstarted thread whose flag is whatever the calling thread's is}
     *
     * @param name the thread's name
     * @param task what the thread runs
     */
    public static Thread undecided(String name, Runnable task) {
        return new Thread(task, name);
    }

    /**
     * {@return {@code thread}, given setDaemon(true) here, where the agent cannot see it (#856)}
     *
     * @param thread an unstarted thread, constructed anywhere
     */
    public static Thread decideDaemon(Thread thread) {
        thread.setDaemon(true);
        return thread;
    }

    /**
     * {@return an unstarted thread given setDaemon(false) here}
     *
     * @param name the thread's name
     * @param task what the thread runs
     */
    public static Thread nonDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(false);
        return thread;
    }
}
