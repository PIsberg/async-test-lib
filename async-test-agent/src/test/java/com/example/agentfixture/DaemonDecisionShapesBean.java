package com.example.agentfixture;

import java.util.concurrent.ThreadFactory;

/**
 * Starts threads whose daemon flag is decided, or left undecided, somewhere other than a
 * {@code setDaemon} call in this class (#737): on a {@code Thread.Builder}, in a subclass, or in
 * a class the agent does not weave.
 */
public class DaemonDecisionShapesBean {

    /**
     * Builds a thread with {@code daemon()} and starts it with a separate {@code start()}.
     *
     * @param name the thread's name, which is how the test tells the findings apart
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builtDaemonThenStarted(String name, Runnable task) {
        Thread thread = Thread.ofPlatform().name(name).daemon().unstarted(task);
        thread.start();
        return thread;
    }

    /**
     * Starts a thread through a builder given {@code daemon(true)}.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builderDaemonStart(String name, Runnable task) {
        return Thread.ofPlatform().name(name).daemon(true).start(task);
    }

    /**
     * Builds a thread with no daemon decision and starts it with a separate {@code start()}.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builtUndecidedThenStarted(String name, Runnable task) {
        Thread thread = Thread.ofPlatform().name(name).unstarted(task);
        thread.start();
        return thread;
    }

    /**
     * Starts a thread through a builder that was never given a daemon decision.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builderUndecidedStart(String name, Runnable task) {
        return Thread.ofPlatform().name(name).start(task);
    }

    /**
     * Constructs a {@link UndecidedWorkerThread} and starts it; neither decides the flag.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread subclassStarted(String name, Runnable task) {
        Thread thread = new UndecidedWorkerThread(name, task);
        thread.start();
        return thread;
    }

    /**
     * Makes a thread through the factory of a builder that was never given a daemon decision, and
     * starts it (#856).
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builderFactoryStarted(String name, Runnable task) {
        ThreadFactory factory = Thread.ofPlatform().name(name).factory();
        Thread thread = factory.newThread(task);
        thread.start();
        return thread;
    }

    /**
     * Makes a thread through the factory of a builder given {@code daemon(true)}, and starts it.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the started thread
     */
    public Thread builderFactoryDaemonStarted(String name, Runnable task) {
        ThreadFactory factory = Thread.ofPlatform().name(name).daemon(true).factory();
        Thread thread = factory.newThread(task);
        thread.start();
        return thread;
    }

    /**
     * Constructs a thread here, in woven code, and leaves its flag and its start to others.
     *
     * @param name the thread's name
     * @param task what the thread runs
     * @return the unstarted thread
     */
    public Thread construct(String name, Runnable task) {
        return new Thread(task, name);
    }

    /**
     * Starts a thread somebody else constructed and configured.
     *
     * @param thread a thread that has not started
     * @return the same thread, started
     */
    public Thread start(Thread thread) {
        thread.start();
        return thread;
    }
}
