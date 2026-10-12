package io.vertx.core.impl;

import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * Test stand-in for Vert.x's thread class, by its real name: an event loop and a worker are both
 * one, told apart by {@code isWorker()}, and both are Netty threads that do not permit blocking.
 */
public class VertxThread extends FastThreadLocalThread {

    private final boolean worker;

    public VertxThread(Runnable task, boolean worker) {
        super(task, false);
        this.worker = worker;
    }

    public boolean isWorker() {
        return worker;
    }
}
