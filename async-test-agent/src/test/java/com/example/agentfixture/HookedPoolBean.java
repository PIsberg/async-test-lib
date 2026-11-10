package com.example.agentfixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@code ThreadPoolExecutor} subclass with a {@code beforeExecute} and an {@code afterExecute}, a
 * rejection handler of its own, and code that reads the pool's queue (#1173). Woven, so the agent
 * rewrites the three overrides to be handed the caller's task and the {@code getQueue} call to
 * show it.
 */
public final class HookedPoolBean {

    /** One thread, one queue slot: a third task is refused. */
    public static final class HookedPool extends ThreadPoolExecutor {
        public final List<Runnable> before = new CopyOnWriteArrayList<>();
        public final List<Runnable> after = new CopyOnWriteArrayList<>();

        public HookedPool(RejectedExecutionHandler handler) {
            super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), handler);
        }

        @Override
        protected void beforeExecute(Thread worker, Runnable task) {
            before.add(task);
        }

        @Override
        protected void afterExecute(Runnable task, Throwable thrown) {
            after.add(task);
        }
    }

    /** Keeps each task it is handed. */
    public static final class KeepingHandler implements RejectedExecutionHandler {
        public final List<Runnable> rejected = new CopyOnWriteArrayList<>();

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            rejected.add(task);
        }
    }

    /** Gives {@code task} to {@code pool} through a woven {@code execute}. */
    public void execute(ThreadPoolExecutor pool, Runnable task) {
        pool.execute(task);
    }

    /** {@return what the pool's queue holds, read through a woven {@code getQueue}} */
    public List<Runnable> queued(ThreadPoolExecutor pool) {
        return new ArrayList<>(pool.getQueue());
    }
}
