package se.deversity.asynctest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks of the detectors that judge one event on one thread see the threads a worker hands
 * work to: a thread it starts through the woven {@code Thread.start}, and a task it submits to a
 * JDK executor. Before, those hooks read the context on the calling thread only, so a
 * {@code System.gc()}, a sleep under a lock, an unlock after a failed {@code tryLock}, a
 * read-to-write upgrade, an {@code obtrudeValue} or a reassigned monitor field was reported on a
 * worker and silent one {@code new Thread(...).start()} away.
 *
 * <p>The hooks are called directly, which is what the woven call sites do. Every case has a twin
 * doing the correct thing on the same kind of thread, which must stay silent, and the event on a
 * thread no run started must reach no detector.
 */
class SpawnedWorkEventFeedTest {

    /** A body step that may throw. */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** How the worker hands {@code work} over and waits for it. */
    private enum HandOver {
        STARTED_THREAD {
            @Override
            void run(ThrowingRunnable work, AtomicReference<Throwable> failed) throws Exception {
                Thread child = new Thread(() -> runInto(work, failed), "spawned");
                AgentThreadHooks.threadStart(child);
                AgentThreadHooks.threadJoin(child);
            }
        },
        SUBMITTED_TASK {
            @Override
            void run(ThrowingRunnable work, AtomicReference<Throwable> failed) throws Exception {
                AtomicReference<Thread> poolThread = new AtomicReference<>();
                ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "pool-thread");
                    poolThread.set(thread);
                    return thread;
                });
                try {
                    Runnable task = () -> runInto(work, failed);
                    @SuppressWarnings("unchecked")
                    Future<Object> future = (Future<Object>) AgentConcurrencyUtilHooks.submit(executor, task);
                    AgentConcurrencyUtilHooks.get(future);
                } finally {
                    executor.shutdownNow();
                    // Its thread has ended before the run is analysed, as a closed pool's has. A
                    // terminated pool says so while its thread is still on its way out, so the
                    // thread is joined: analysis asks the thread, not the pool.
                    executor.awaitTermination(10, TimeUnit.SECONDS);
                    poolThread.get().join(TimeUnit.SECONDS.toMillis(10));
                }
            }
        };

        abstract void run(ThrowingRunnable work, AtomicReference<Throwable> failed) throws Exception;
    }

    private static void runInto(ThrowingRunnable work, AtomicReference<Throwable> failed) {
        try {
            work.run();
        } catch (Throwable t) { // NOPMD - handed to the test thread
            failed.set(t);
        }
    }

    /**
     * Runs {@code work} on a thread a worker of a run with {@code config} handed it to, and says
     * whether the detector {@code analysis} reads reported.
     */
    private static boolean reported(HandOver handOver, AsyncTestConfig config, ThrowingRunnable work,
                                    Function<AsyncTestContext, Boolean> analysis) throws Exception {
        AsyncTestContext ctx = new AsyncTestContext(config);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                handOver.run(work, failed);
            } catch (Throwable t) { // NOPMD - handed to the test thread below
                failed.set(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        }, "worker-0");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertNull(failed.get(), () -> "the hand-over failed: " + failed.get());
        AsyncTestContext.install(ctx);
        try {
            return analysis.apply(ctx);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static void bothWays(String what, AsyncTestConfig config, ThrowingRunnable buggy,
                                 ThrowingRunnable correct, Function<AsyncTestContext, Boolean> analysis)
            throws Exception {
        for (HandOver handOver : HandOver.values()) {
            assertTrue(reported(handOver, config, buggy, analysis),
                    () -> what + " on a " + handOver + " must reach the run's detector");
            assertFalse(reported(handOver, config, correct, analysis),
                    () -> "the correct twin of " + what + " on a " + handOver + " must stay silent");
        }
    }

    @Test
    @DisplayName("System.gc() on a thread the worker handed work to is reported")
    void explicitGc() throws Exception {
        bothWays("System.gc()", AsyncTestConfig.builder().detectExplicitGc(true).build(),
                AgentGcHooks::gc,
                () -> { },
                ctx -> AsyncTestContext.explicitGcDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a sleep under a lock on a thread the worker handed work to is reported")
    void sleepUnderALock() throws Exception {
        bothWays("a sleep under a lock", AsyncTestConfig.builder().detectSleepInLock(true).build(),
                () -> {
                    Lock lock = new ReentrantLock();
                    AgentLockHooks.lock(lock);
                    try {
                        AgentSleepHooks.sleep(2);
                    } finally {
                        AgentLockHooks.unlock(lock);
                    }
                },
                () -> {
                    Lock lock = new ReentrantLock();
                    AgentLockHooks.lock(lock);
                    AgentLockHooks.unlock(lock);
                    AgentSleepHooks.sleep(2);
                },
                ctx -> AsyncTestContext.sleepInLockDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("an unlock after a failed tryLock on a thread the worker handed work to is reported")
    void unlockAfterAFailedTryLock() throws Exception {
        bothWays("an unlock after a failed tryLock",
                AsyncTestConfig.builder().detectTryLockMisuse(true).build(),
                () -> {
                    ReentrantLock lock = new ReentrantLock();
                    Thread holder = holding(lock);
                    try {
                        assertFalse(AgentLockHooks.tryLock(lock), "another thread holds it");
                        try {
                            AgentLockHooks.unlock(lock);
                        } catch (IllegalMonitorStateException expected) { // NOPMD EmptyCatchBlock - the misuse
                            // The unlock of a lock this thread does not hold is the bug under test.
                        }
                    } finally {
                        holder.interrupt();
                        holder.join();
                    }
                },
                () -> {
                    ReentrantLock lock = new ReentrantLock();
                    if (AgentLockHooks.tryLock(lock)) {
                        AgentLockHooks.unlock(lock);
                    }
                },
                ctx -> AsyncTestContext.tryLockMisuseDetector().analyze().hasIssues());
    }

    /** {@return a started thread that holds {@code lock} until it is interrupted} */
    private static Thread holding(ReentrantLock lock) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                Thread.sleep(TimeUnit.SECONDS.toMillis(20));
            } catch (InterruptedException released) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "holder");
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS), "the holder took the lock");
        return holder;
    }

    @Test
    @DisplayName("a read-to-write upgrade on a thread the worker handed work to is reported")
    void readToWriteUpgrade() throws Exception {
        bothWays("a read-to-write upgrade",
                AsyncTestConfig.builder().detectLockUpgradeDeadlock(true).build(),
                () -> {
                    ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
                    Lock read = AgentLockHooks.readLock(rw);
                    Lock write = AgentLockHooks.writeLock(rw);
                    AgentLockHooks.lock(read);
                    try {
                        assertFalse(AgentLockHooks.tryLock(write), "a reader is never granted the write lock");
                    } finally {
                        AgentLockHooks.unlock(read);
                    }
                },
                () -> {
                    ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
                    Lock read = AgentLockHooks.readLock(rw);
                    Lock write = AgentLockHooks.writeLock(rw);
                    AgentLockHooks.lock(read);
                    AgentLockHooks.unlock(read);
                    assertTrue(AgentLockHooks.tryLock(write), "nothing holds the read lock any more");
                    AgentLockHooks.unlock(write);
                },
                ctx -> AsyncTestContext.lockUpgradeDeadlockDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("an obtrudeValue on a thread the worker handed work to is reported")
    void obtrude() throws Exception {
        bothWays("an obtrudeValue",
                AsyncTestConfig.builder().detectCompletableFutureObtrudeAbuse(true).build(),
                () -> AgentConcurrencyUtilHooks.obtrudeValue(new CompletableFuture<>(), "forced"),
                () -> new CompletableFuture<>().complete("done"),
                ctx -> AsyncTestContext.completableFutureObtrudeDetector().analyze().hasIssues());
    }

    /** An owner whose lock field is not final. */
    private static final class Service {
        @SuppressWarnings("unused") // named by the recorded field id
        Object lock = new Object();
    }

    @Test
    @DisplayName("a reassigned monitor field on a thread the worker handed work to is reported")
    void reassignedMonitorField() throws Exception {
        bothWays("a reassigned monitor field",
                AsyncTestConfig.builder().detectSynchronizedNonFinal(true).build(),
                () -> {
                    Service owner = new Service();
                    AgentMonitorHooks.monitorFieldEntered(new Object(), owner, "Service.lock");
                    AgentMonitorHooks.monitorFieldEntered(new Object(), owner, "Service.lock");
                },
                () -> {
                    Service owner = new Service();
                    Object monitor = new Object();
                    AgentMonitorHooks.monitorFieldEntered(monitor, owner, "Service.lock");
                    AgentMonitorHooks.monitorFieldEntered(monitor, owner, "Service.lock");
                },
                ctx -> AsyncTestContext.synchronizedNonFinalDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("the same events on a thread no run started reach no detector and do not throw")
    void aThreadOfNoRun() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread stray = new Thread(() -> runInto(() -> {
            AgentGcHooks.gc();
            AgentConcurrencyUtilHooks.obtrudeValue(new CompletableFuture<>(), "forced");
            Lock lock = new ReentrantLock();
            AgentLockHooks.lock(lock);
            AgentSleepHooks.sleep(1);
            AgentLockHooks.unlock(lock);
        }, failed), "stray");
        stray.start();
        stray.join(TimeUnit.SECONDS.toMillis(20));
        assertDoesNotThrow(() -> assertNull(failed.get(), () -> "a stray thread's hook threw: " + failed.get()));
    }

    // ---- Detectors that judge what a thread left behind (#1339) ----

    @Test
    @DisplayName("a lock a handed-over thread took and ended holding is reported")
    void lockNeverReleased() throws Exception {
        bothWays("a lock never released", AsyncTestConfig.builder().detectLockLeaks(true).build(),
                () -> AgentLockHooks.lock(new ReentrantLock()),
                () -> {
                    Lock lock = new ReentrantLock();
                    AgentLockHooks.lock(lock);
                    AgentLockHooks.unlock(lock);
                },
                ctx -> AsyncTestContext.lockLeakDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a wait outside a loop on a handed-over thread is reported")
    void waitOutsideALoop() throws Exception {
        bothWays("a wait outside a loop",
                AsyncTestConfig.builder().detectSpuriousWakeupHazard(true).build(),
                () -> {
                    Object monitor = new Object();
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorWait(monitor, 2);
                    }
                },
                () -> {
                    Object monitor = new Object();
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorWait(monitor, 2);
                        AgentMonitorHooks.loopBackEdge();
                    }
                },
                ctx -> AsyncTestContext.spuriousWakeupHazardDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a latch await that timed out on a handed-over thread is reported")
    void latchAwaitTimedOut() throws Exception {
        bothWays("a timed-out latch await",
                AsyncTestConfig.builder().detectCountDownLatchIssues(true).build(),
                () -> assertFalse(AgentConcurrencyUtilHooks.await(new CountDownLatch(1), 2, TimeUnit.MILLISECONDS)),
                () -> {
                    CountDownLatch latch = new CountDownLatch(1);
                    AgentConcurrencyUtilHooks.countDown(latch);
                    assertTrue(AgentConcurrencyUtilHooks.await(latch, 2, TimeUnit.SECONDS));
                },
                ctx -> AsyncTestContext.countDownLatchDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a wait outside a loop in a task is reported while its pool stays open")
    void waitOutsideALoopOnAPoolLeftOpen() throws Exception {
        AsyncTestContext ctx = new AsyncTestContext(
                AsyncTestConfig.builder().detectSpuriousWakeupHazard(true).build());
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<Thread> poolThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "open-pool-thread");
            poolThread.set(thread);
            return thread;
        });
        try {
            Thread worker = new Thread(() -> {
                AsyncTestContext.install(ctx);
                try {
                    Runnable task = () -> runInto(() -> {
                        Object monitor = new Object();
                        synchronized (monitor) {
                            AgentMonitorHooks.monitorWait(monitor, 2);
                        }
                    }, failed);
                    @SuppressWarnings("unchecked")
                    Future<Object> future = (Future<Object>) AgentConcurrencyUtilHooks.submit(executor, task);
                    AgentConcurrencyUtilHooks.get(future);
                } catch (Throwable t) { // NOPMD - handed to the test thread below
                    failed.set(t);
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "worker-0");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            assertNull(failed.get(), () -> "the hand-over failed: " + failed.get());
            // The task has ended once its thread is back waiting for the pool's next one.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (poolThread.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            AsyncTestContext.install(ctx);
            try {
                assertTrue(AsyncTestContext.spuriousWakeupHazardDetector().analyze().hasIssues(),
                        "the task returned without a back-edge; its thread idling in the pool cannot reach one");
            } finally {
                AsyncTestContext.uninstall();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A started thread that is still running when the run is analysed has left nothing behind
     * yet: the lock it holds it may release, and the wakeup it has not looped on it may loop on.
     * {@code atAnalysis} runs with the thread parked after {@code work}.
     */
    private static boolean reportedWhileStillRunning(AsyncTestConfig config, ThrowingRunnable work,
                                                     Function<AsyncTestContext, Boolean> analysis)
            throws Exception {
        AsyncTestContext ctx = new AsyncTestContext(config);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        CountDownLatch worked = new CountDownLatch(1);
        CountDownLatch analysed = new CountDownLatch(1);
        Thread child = new Thread(() -> runInto(() -> {
            work.run();
            worked.countDown();
            analysed.await();
        }, failed), "still-running");
        Thread worker = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                AgentThreadHooks.threadStart(child);
            } finally {
                AsyncTestContext.uninstall();
            }
        }, "worker-0");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        try {
            assertTrue(worked.await(10, TimeUnit.SECONDS), "the started thread did its work");
            AsyncTestContext.install(ctx);
            try {
                return analysis.apply(ctx);
            } finally {
                AsyncTestContext.uninstall();
            }
        } finally {
            analysed.countDown();
            child.join(TimeUnit.SECONDS.toMillis(20));
            assertNull(failed.get(), () -> "the started thread failed: " + failed.get());
        }
    }

    @Test
    @DisplayName("a started thread still running at analysis is not read as having leaked")
    void aThreadStillRunningLeftNothingBehind() throws Exception {
        assertFalse(reportedWhileStillRunning(AsyncTestConfig.builder().detectLockLeaks(true).build(),
                        () -> AgentLockHooks.lock(new ReentrantLock()),
                        ctx -> AsyncTestContext.lockLeakDetector().analyze().hasIssues()),
                "the thread holding the lock is still working and may release it");
        assertFalse(reportedWhileStillRunning(
                        AsyncTestConfig.builder().detectSpuriousWakeupHazard(true).build(),
                        () -> {
                            Object monitor = new Object();
                            synchronized (monitor) {
                                AgentMonitorHooks.monitorWait(monitor, 2);
                            }
                        },
                        ctx -> AsyncTestContext.spuriousWakeupHazardDetector().analyze().hasIssues()),
                "the thread has not reached its loop's back-edge yet, and may");
    }

    // ---- A signal no thread was waiting for, and a latch nobody counted down (#1339) ----

    @Test
    @DisplayName("a notify with no waiter, then a wait under an if, on a handed-over thread is reported")
    void notifyBeforeTheWait() throws Exception {
        bothWays("a notify before the wait",
                AsyncTestConfig.builder().detectMissedSignals(true).build(),
                () -> {
                    Object monitor = new Object();
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorNotify(monitor);
                    }
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorWait(monitor, 2);
                    }
                },
                () -> {
                    Object monitor = new Object();
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorNotify(monitor);
                    }
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorWait(monitor, 2);
                        AgentMonitorHooks.loopBackEdge();
                    }
                },
                ctx -> AsyncTestContext.missedSignalDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a signal with no waiter, then an await under an if, on a handed-over thread is reported")
    void signalBeforeTheAwait() throws Exception {
        bothWays("a signal before the await",
                AsyncTestConfig.builder().detectConditionVariableIssues(true).build(),
                () -> {
                    ReentrantLock lock = new ReentrantLock();
                    Condition ready = lock.newCondition();
                    lock.lock();
                    try {
                        AgentMonitorHooks.conditionSignal(ready);
                        AgentMonitorHooks.conditionAwait(ready, 2, TimeUnit.MILLISECONDS);
                    } finally {
                        lock.unlock();
                    }
                },
                () -> {
                    ReentrantLock lock = new ReentrantLock();
                    Condition ready = lock.newCondition();
                    lock.lock();
                    try {
                        AgentMonitorHooks.conditionSignal(ready);
                        AgentMonitorHooks.conditionAwait(ready, 2, TimeUnit.MILLISECONDS);
                        AgentMonitorHooks.loopBackEdge();
                    } finally {
                        lock.unlock();
                    }
                },
                ctx -> AsyncTestContext.conditionVariableDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("an await that gave up on a latch nobody counted down, on a handed-over thread, is reported")
    void latchNobodyCountedDown() throws Exception {
        bothWays("an await on a latch nobody counted down",
                AsyncTestConfig.builder().detectLatchMisuse(true).build(),
                () -> assertFalse(AgentConcurrencyUtilHooks.await(new CountDownLatch(1), 2, TimeUnit.MILLISECONDS)),
                () -> {
                    CountDownLatch latch = new CountDownLatch(1);
                    AgentConcurrencyUtilHooks.countDown(latch);
                    assertTrue(AgentConcurrencyUtilHooks.await(latch, 2, TimeUnit.SECONDS));
                },
                ctx -> AsyncTestContext.latchMisuseDetector().analyze().hasIssues());
    }

    @Test
    @DisplayName("a started thread's notify that found no waiter counts against a worker's later wait")
    void aStartedThreadsLostNotifyStrandsTheWorker() throws Exception {
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder().detectMissedSignals(true).build());
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Object monitor = new Object();
        Thread worker = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                Thread child = new Thread(() -> runInto(() -> {
                    synchronized (monitor) {
                        AgentMonitorHooks.monitorNotify(monitor);
                    }
                }, failed), "notifier");
                AgentThreadHooks.threadStart(child);
                AgentThreadHooks.threadJoin(child);
                synchronized (monitor) {
                    AgentMonitorHooks.monitorWait(monitor, 2);
                }
            } catch (Throwable t) { // NOPMD - handed to the test thread below
                failed.set(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        }, "worker-0");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertNull(failed.get(), () -> "the body failed: " + failed.get());
        AsyncTestContext.install(ctx);
        try {
            assertTrue(AsyncTestContext.missedSignalDetector().analyze().hasIssues(),
                    "the notify came before the wait, on a thread the worker started and joined");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a started thread still between its wakeup and its loop's back-edge is not a lost signal")
    void aThreadStillRunningHasNotMissedItsSignal() throws Exception {
        assertFalse(reportedWhileStillRunning(AsyncTestConfig.builder().detectMissedSignals(true).build(),
                        () -> {
                            Object monitor = new Object();
                            synchronized (monitor) {
                                AgentMonitorHooks.monitorNotify(monitor);
                            }
                            synchronized (monitor) {
                                AgentMonitorHooks.monitorWait(monitor, 2);
                            }
                        },
                        ctx -> AsyncTestContext.missedSignalDetector().analyze().hasIssues()),
                "the thread has not reached its loop's back-edge yet, and may");
    }
}
