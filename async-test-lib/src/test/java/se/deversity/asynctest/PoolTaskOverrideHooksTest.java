package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code ThreadPoolExecutor} whose task-facing overrides or rejection handler the agent wove, and
 * the pool's queue as woven code reads it (#1173).
 *
 * <p>The weaver inserts {@link AgentConcurrencyUtilHooks#poolTask} at the entry of a
 * {@code beforeExecute}, {@code afterExecute} or {@code rejectedExecution} and reports the class
 * through {@link AgentConcurrencyUtilHooks#poolTaskOverrideWoven}; the classes here do both by
 * hand, as the woven bytes would. Each test uses classes of its own, since the report is kept by
 * class name for the life of the JVM.
 */
class PoolTaskOverrideHooksTest {

    private final List<ThreadPoolExecutor> made = new ArrayList<>();

    @AfterEach
    void shutDown() {
        made.forEach(ThreadPoolExecutor::shutdownNow);
        AsyncTestContext.uninstall();
    }

    private static void installed() {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    private <P extends ThreadPoolExecutor> P kept(P pool) {
        made.add(pool);
        return pool;
    }

    /** Occupies the pool's one thread until {@code release} opens, so later tasks queue. */
    private static void occupy(ThreadPoolExecutor pool, CountDownLatch release) {
        pool.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** A pool whose overrides the agent wove: each is handed what the caller gave. */
    static final class WovenPool extends ThreadPoolExecutor {
        final List<Runnable> before = new CopyOnWriteArrayList<>();
        final List<Runnable> after = new CopyOnWriteArrayList<>();

        WovenPool() {
            super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread worker, Runnable task) {
            before.add(AgentConcurrencyUtilHooks.poolTask(task));
        }

        @Override
        protected void afterExecute(Runnable task, Throwable thrown) {
            after.add(AgentConcurrencyUtilHooks.poolTask(task));
        }
    }

    /** The same overrides, in a class the agent never wove. */
    static final class UnwovenPool extends ThreadPoolExecutor {
        UnwovenPool() {
            super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread worker, Runnable task) {
            super.beforeExecute(worker, task);
        }
    }

    /** A rejection handler the agent wove. */
    static final class WovenHandler implements RejectedExecutionHandler {
        final List<Runnable> rejected = new CopyOnWriteArrayList<>();

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            rejected.add(AgentConcurrencyUtilHooks.poolTask(task));
        }
    }

    @Test
    @DisplayName("a pool whose beforeExecute and afterExecute were woven is wrapped for, and both see the caller's task")
    void wovenOverridesAreWrappedFor() throws InterruptedException {
        AgentConcurrencyUtilHooks.poolTaskOverrideWoven(WovenPool.class.getName());
        installed();
        WovenPool pool = kept(new WovenPool());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        occupy(pool, release);
        Runnable mine = ran::countDown;
        AgentConcurrencyUtilHooks.execute(pool, mine);

        assertNotSame(mine, pool.getQueue().peek(),
                "the task waits in the queue as the wrapper that orders it after the execute");
        release.countDown();
        assertTrue(ran.await(10, TimeUnit.SECONDS), "the task never ran");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(pool.before.contains(mine), "beforeExecute was handed " + pool.before);
        assertTrue(pool.after.contains(mine), "afterExecute was handed " + pool.after);
    }

    @Test
    @DisplayName("a pool whose override was not woven is still left alone")
    void unwovenOverridesAreLeftAlone() {
        installed();
        UnwovenPool pool = kept(new UnwovenPool());
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupy(pool, release);
            Runnable mine = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, mine);

            assertSame(mine, pool.getQueue().peek(), "its beforeExecute would be handed a wrapper");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("a pool with a woven rejection handler is wrapped for, and the handler sees the caller's task")
    void wovenHandlerIsWrappedFor() {
        AgentConcurrencyUtilHooks.poolTaskOverrideWoven(WovenHandler.class.getName());
        installed();
        WovenHandler handler = new WovenHandler();
        ThreadPoolExecutor pool = kept(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), handler));
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupy(pool, release);
            Runnable queued = () -> { };
            Runnable refused = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, queued);
            AgentConcurrencyUtilHooks.execute(pool, refused);

            assertNotSame(queued, pool.getQueue().peek(), "the queued task is wrapped");
            assertEquals(List.of(refused), handler.rejected, "the handler is handed the task itself");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("getQueue, as woven code reads it, holds the caller's tasks and removes them by those tasks")
    void wovenGetQueueShowsTheCallersTasks() {
        installed();
        ThreadPoolExecutor pool = kept(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()));
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupy(pool, release);
            Runnable first = () -> { };
            Runnable second = () -> { };
            Runnable third = () -> { };
            AgentConcurrencyUtilHooks.execute(pool, first);
            AgentConcurrencyUtilHooks.execute(pool, second);
            AgentConcurrencyUtilHooks.execute(pool, third);
            assertNotSame(first, pool.getQueue().peek(), "premise: the pool queues wrappers");

            BlockingQueue<Runnable> queue = AgentConcurrencyUtilHooks.poolQueue(pool);
            assertEquals(3, queue.size());
            assertEquals(List.of(first, second, third), new ArrayList<>(queue), "iteration");
            assertSame(first, queue.peek());
            assertTrue(queue.contains(second));
            assertTrue(queue.remove(second), "removed by the caller's own task");
            assertFalse(queue.contains(second));
            assertEquals(2, pool.getQueue().size(), "the pool's own queue lost it");
            assertSame(first, queue.poll());
            List<Runnable> drained = new ArrayList<>();
            assertEquals(1, queue.drainTo(drained));
            assertEquals(List.of(third), drained);
            assertTrue(pool.getQueue().isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("outside a run, getQueue hands back the pool's own queue")
    void getQueueOutsideARunIsThePoolsOwn() {
        ThreadPoolExecutor pool = kept(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()));
        assertSame(pool.getQueue(), AgentConcurrencyUtilHooks.poolQueue(pool));
    }
}
