package com.example.agentfixture;

import java.util.concurrent.ThreadFactory;

/**
 * Makes its threads through a {@code Thread.Builder}, deciding the daemon flag on the builder or
 * not at all (#737). Named, and given a handler, so the daemon flag is the only thing a report can
 * be about.
 */
public class BuilderThreadFactory implements ThreadFactory {

    private final boolean decides;

    /**
     * @param decides whether the builder is given {@code daemon(true)}
     */
    public BuilderThreadFactory(boolean decides) {
        this.decides = decides;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread.Builder.OfPlatform builder = Thread.ofPlatform().name("builder-worker")
                .uncaughtExceptionHandler((t, e) -> { });
        if (decides) {
            builder.daemon(true);
        }
        return builder.unstarted(task);
    }
}
