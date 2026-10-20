package com.example.agentfixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Unmodified buggy code and its fixed twin, one pair per bug class a recording-only detector names,
 * for {@code AgentFeedOverlapAuditTest}: which agent-fed detector, if any, already reports the bug
 * on code that records nothing.
 */
public final class FeedAuditSubjects {

    private FeedAuditSubjects() {
    }

    /** The control: the README's bare counter++, which AtomicityValidator reports through the agent. */
    public static final class Counter {
        private int count;

        /** A compound read-modify-write with no lock. */
        public void increment() {
            count++;
        }
    }

    /** CONCURRENT_MODIFICATIONS: one thread iterates a list while another adds to it. */
    public static final class SharedList {
        private final List<Integer> items = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));

        /** Iterates with no lock. */
        public int sum() {
            int sum = 0;
            for (int item : items) {
                sum += item;
            }
            return sum;
        }

        /** Adds with no lock. */
        public void add(int value) {
            items.add(value);
        }

        /** Iterates under the list's own monitor. */
        public int sumGuarded() {
            synchronized (items) {
                int sum = 0;
                for (int item : items) {
                    sum += item;
                }
                return sum;
            }
        }

        /** Adds under the list's own monitor. */
        public void addGuarded(int value) {
            synchronized (items) {
                items.add(value);
            }
        }
    }

    /** VISIBILITY: data published behind a flag, plain or volatile. */
    public static final class Publication {
        private int data;
        private boolean ready;
        private volatile boolean readyVolatile;

        /** Stores the data, then a plain flag. */
        public void publish() {
            data = 42;
            ready = true;
        }

        /** Reads the plain flag, then the data. */
        public int consume() {
            return ready ? data : -1;
        }

        /** Stores the data, then a volatile flag: the store releases the data. */
        public void publishVolatile() {
            data = 42;
            readyVolatile = true;
        }

        /** Reads the volatile flag, then the data. */
        public int consumeVolatile() {
            return readyVolatile ? data : -1;
        }

        /** Stores a computed value, then a plain flag: the data field is not a constant write. */
        public void publishComputed(int value) {
            data = value;
            ready = true;
        }
    }

    /** VISIBILITY: a static stop flag, plain or volatile, set by one thread and polled by another. */
    public static final class StaticStopFlag {
        private static boolean stopped;
        private static volatile boolean stoppedVolatile;

        private StaticStopFlag() {
        }

        /** Sets the plain flag. */
        public static void stop() {
            stopped = true;
        }

        /** {@return the plain flag} */
        public static boolean isStopped() {
            return stopped;
        }

        /** Sets the volatile flag. */
        public static void stopVolatile() {
            stoppedVolatile = true;
        }

        /** {@return the volatile flag} */
        public static boolean isStoppedVolatile() {
            return stoppedVolatile;
        }
    }

    /** COMPLETABLE_FUTURE_COMPLETION_LEAKS: a future left pending on the exception path. */
    public static CompletableFuture<String> fetchLeaking(boolean fail) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            if (fail) {
                throw new IllegalStateException("backend down");
            }
            future.complete("ok");
        } catch (IllegalStateException e) {
            // the bug: the future is never completed on this path
        }
        return future;
    }

    /** The twin: the exception path completes the future too. */
    public static CompletableFuture<String> fetchCompleting(boolean fail) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            if (fail) {
                throw new IllegalStateException("backend down");
            }
            future.complete("ok");
        } catch (IllegalStateException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** THREAD_POOL: a saturated pool whose rejections the caller swallows, or runs itself. */
    public static final class Pool {
        private final ThreadPoolExecutor swallowing = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        private final ThreadPoolExecutor callerRuns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());

        /** Submits, and drops the task if the pool is full: the work is silently lost. */
        public void submitDropping(Runnable task) {
            try {
                swallowing.execute(task);
            } catch (RejectedExecutionException e) {
                // the bug: the task is lost and nobody hears of it
            }
        }

        /** Submits; a full pool runs the task on the caller instead. */
        public void submitCallerRuns(Runnable task) {
            callerRuns.execute(task);
        }

        /** Shuts both pools down. */
        public void shutdown() {
            swallowing.shutdownNow();
            callerRuns.shutdownNow();
        }
    }

    /** THREAD_LEAKS: a thread that outlives the body that started it. */
    public static Thread startAndForget() {
        Thread thread = new Thread(() -> pause(1_500), "audit-leaked");
        thread.start();
        return thread;
    }

    /** The twin: the body joins what it starts. */
    public static void startAndJoin() throws InterruptedException {
        Thread thread = new Thread(() -> pause(20), "audit-joined");
        thread.start();
        thread.join();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
