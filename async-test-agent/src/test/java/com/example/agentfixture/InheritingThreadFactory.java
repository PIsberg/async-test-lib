package com.example.agentfixture;

import java.util.concurrent.ThreadFactory;

/**
 * Names its threads and gives them a handler, and never calls setDaemon: the thread gets whatever
 * flag the calling thread has, which on a runner worker is daemon.
 */
public class InheritingThreadFactory implements ThreadFactory {

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "inheriting-worker");
        thread.setUncaughtExceptionHandler((t, e) -> { });
        return thread;
    }
}
