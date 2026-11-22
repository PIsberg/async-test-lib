package com.example.agentfixture;

import java.util.List;

/**
 * Every shape in which a class constructs a thread, for the construction marker (#737) to be
 * checked against: each must verify once woven, and each thread must be marked exactly once.
 */
public class ThreadConstructionSample {

    /** {@code new Thread(group, task)} (#1401). */
    public static Thread inAGroup(Runnable task) {
        return new Thread(Thread.currentThread().getThreadGroup(), task);
    }

    /** {@code new Thread(group, task, name, stackSize)} (#1401). */
    public static Thread inAGroupWithAStackSize(Runnable task) {
        return new Thread(Thread.currentThread().getThreadGroup(), task, "sized", 256 * 1024L);
    }

    /**
     * {@code new Thread(group, task, name, stackSize, inheritThreadLocals)} (#1442).
     *
     * @param task    what the thread runs
     * @param inherit whether the thread inherits the constructing thread's inheritable locals
     * @return the thread, unstarted
     */
    public static Thread inAGroupWithAStackSizeInheriting(Runnable task, boolean inherit) {
        return new Thread(Thread.currentThread().getThreadGroup(), task, "inheriting", 256 * 1024L, inherit);
    }

    /** {@code new Thread(group, task, name)} (#1401). */
    public static Thread inAGroupNamed(Runnable task) {
        return new Thread(Thread.currentThread().getThreadGroup(), task, "grouped");
    }

    /**
     * {@return a plain {@code new Thread}}
     *
     * @param task what the thread would run
     */
    public static Thread plain(Runnable task) {
        return new Thread(task);
    }

    /**
     * Constructs a thread and drops it, which javac compiles to {@code NEW, DUP, <init>, POP}.
     *
     * @param task what the thread would run
     */
    public static void discarded(Runnable task) {
        new Thread(task);
    }

    /**
     * {@return a thread constructed around another, so two constructor calls nest}
     *
     * @param task what the inner thread would run
     */
    public static Thread nested(Runnable task) {
        return new Thread(new Thread(task));
    }

    /**
     * {@return a thread constructed as another call's argument}
     *
     * @param task what the thread would run
     */
    public static List<Thread> asAnArgument(Runnable task) {
        return List.of(new Thread(task, "argument"));
    }

    /**
     * {@return a thread whose argument has a branch in it}
     *
     * @param task   what the thread runs when {@code chosen}
     * @param chosen which task to use
     */
    public static Thread withABranchInItsArguments(Runnable task, boolean chosen) {
        return new Thread(chosen ? task : () -> { });
    }

    /** A direct subclass handing its task straight to its superclass constructor (#1401). */
    public static final class TaskThread extends Thread {

        /**
         * @param task what the thread runs, handed to {@code Thread(Runnable)}
         */
        public TaskThread(Runnable task) {
            super(task);
        }

        /**
         * @param task what the thread runs
         * @param name the thread's name, handed with it to {@code Thread(Runnable, String)}
         */
        public TaskThread(Runnable task, String name) {
            super(task, name);
        }

        /**
         * @param task      what the thread runs
         * @param stackSize handed with the task to the stack-size constructor
         */
        public TaskThread(Runnable task, long stackSize) {
            super(null, task, "sized", stackSize);
        }

        /** Hands nothing on: {@code Thread()}. */
        public TaskThread() {
            super();
        }
    }

    /** A direct subclass that constructs another thread inside its superclass call. */
    public static final class WrappingThread extends Thread {

        /**
         * @param task what the inner thread runs, which this thread runs in turn
         */
        public WrappingThread(Runnable task) {
            super(new Thread(task));
        }

        /** Delegates, so only the constructor it delegates to marks. */
        public WrappingThread() {
            this(() -> { });
        }
    }

    /** A second-level subclass that constructs an instance of its superclass in its super call. */
    public static final class DeepThread extends UndecidedWorkerThread {

        /**
         * @param task what the thread runs
         */
        public DeepThread(Runnable task) {
            super(new UndecidedWorkerThread("scratch", task).getName(), task);
        }
    }
}
