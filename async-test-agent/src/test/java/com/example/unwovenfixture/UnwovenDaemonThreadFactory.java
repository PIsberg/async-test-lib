package com.example.unwovenfixture;

import java.util.concurrent.ThreadFactory;

/**
 * Names its threads, gives them a handler and calls setDaemon(true), in a package the weaver never
 * scans (#737): the decision is made, and the agent cannot see it.
 */
public class UnwovenDaemonThreadFactory implements ThreadFactory {

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "unwoven-deciding-worker");
        thread.setUncaughtExceptionHandler((t, e) -> { });
        thread.setDaemon(true);
        return thread;
    }
}
