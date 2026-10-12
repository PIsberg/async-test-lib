package com.example.agentfixture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uses thread-locals from pooled tasks the three ways a service does, recording nothing: a
 * request context set per task and left behind, the same context removed in {@code finally}, and a
 * per-thread buffer set once and reused. The pool has one thread, so every task runs on the thread
 * the one before it used. Woven with {@code collections=true}, the thread-local calls and the
 * {@code submit} reach the hooks.
 */
public class ThreadLocalTaskBean implements AutoCloseable {

    /** A request context: set for each request, meant to be removed when the request ends. */
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    /** A per-thread cache: set on first use and kept for the thread's life, by design. */
    private static final ThreadLocal<StringBuilder> BUFFER = new ThreadLocal<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(1);

    /** What the last unauthenticated task saw, so the leak is observable. */
    public final AtomicReference<String> seenByTheNextTask = new AtomicReference<>();

    /**
     * Two requests set the user and never remove it, then a task that sets nothing reads it.
     *
     * @throws Exception if a task fails
     */
    public void leaveTheUserBehind() throws Exception {
        run(() -> CURRENT_USER.set("alice"));
        run(() -> CURRENT_USER.set("bob"));
        run(() -> seenByTheNextTask.set(CURRENT_USER.get()));
    }

    /**
     * The same requests, each removing the user in {@code finally}.
     *
     * @throws Exception if a task fails
     */
    public void removeTheUserInFinally() throws Exception {
        run(() -> withUser("alice"));
        run(() -> withUser("bob"));
        run(() -> seenByTheNextTask.set(CURRENT_USER.get()));
    }

    private static void withUser(String user) {
        CURRENT_USER.set(user);
        try {
            Thread.onSpinWait();
        } finally {
            CURRENT_USER.remove();
        }
    }

    /**
     * Three tasks reuse one per-thread buffer, the first creating it.
     *
     * @throws Exception if a task fails
     */
    public void reuseAPerThreadBuffer() throws Exception {
        for (int i = 0; i < 3; i++) {
            run(() -> {
                StringBuilder buffer = BUFFER.get();
                if (buffer == null) {
                    buffer = new StringBuilder();
                    BUFFER.set(buffer);
                }
                buffer.setLength(0);
                buffer.append(Thread.currentThread().getName());
            });
        }
    }

    private void run(Runnable task) throws InterruptedException, ExecutionException {
        pool.submit(task).get();
    }

    @Override
    public void close() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
