package com.example.agentfixture;

/** A thread subclass whose constructor leaves the daemon flag to inheritance. */
public class UndecidedWorkerThread extends Thread {

    /**
     * Makes the thread without deciding its daemon flag.
     *
     * @param name the thread's name
     * @param task what the thread runs
     */
    public UndecidedWorkerThread(String name, Runnable task) {
        super(task, name);
    }
}
