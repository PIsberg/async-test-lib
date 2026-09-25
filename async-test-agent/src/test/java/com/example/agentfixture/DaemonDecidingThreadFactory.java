package com.example.agentfixture;

import java.util.concurrent.ThreadFactory;

/**
 * The twin of {@link InheritingThreadFactory} that decides: it calls setDaemon(true) itself.
 */
public class DaemonDecidingThreadFactory implements ThreadFactory {

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "deciding-worker");
        thread.setUncaughtExceptionHandler((t, e) -> { });
        thread.setDaemon(true);
        return thread;
    }
}
