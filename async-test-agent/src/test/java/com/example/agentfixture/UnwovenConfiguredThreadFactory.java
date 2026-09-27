package com.example.agentfixture;

import com.example.unwovenfixture.UnwovenThreadMaker;

import java.util.concurrent.ThreadFactory;

/**
 * Constructs its threads here, in woven code, and has their daemon flag decided by a call into a
 * class the agent does not weave (#856).
 */
public class UnwovenConfiguredThreadFactory implements ThreadFactory {

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "unwoven-configured-worker");
        thread.setUncaughtExceptionHandler((t, e) -> { });
        return UnwovenThreadMaker.decideDaemon(thread);
    }
}
