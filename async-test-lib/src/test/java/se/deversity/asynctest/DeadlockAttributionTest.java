package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whose deadlock a deadlock is, and whether a lock deadlock between virtual workers is seen at all.
 *
 * <p>Each test leaks permanently deadlocked threads; surefire gives this class a JVM of its own, and
 * {@code DeadlockDetector} baselines what is already deadlocked when a run starts, so the tests do
 * not see each other's leftovers.
 */
class DeadlockAttributionTest {

    private static AsyncTestConfig.Builder deadlockOnly() {
        // builder() enables deadlock detection and nothing else.
        return AsyncTestConfig.builder().licenseMockMode(true).failOn(FailOn.CRITICAL);
    }

    /**
     * #1385. Fails if a run reports a deadlock none of its threads is in: the clean run then fails
     * under failOn = CRITICAL with "Circular lock dependency found between JVM threads". Also fails
     * if the deadlocking run stops reporting its own deadlock.
     */
    @Test
    void aCleanRunDoesNotInheritAParallelRunsDeadlock() throws Exception {
        // The deadlock has to form while the clean run is in progress: one that already exists
        // when a run starts is baselined away, which is a different case and already handled.
        CountDownLatch cleanRunStarted = new CountDownLatch(1);
        CountDownLatch bothHoldTheirFirst = new CountDownLatch(2);
        CompletableFuture<Throwable> deadlocking = runOnItsOwnThread(() -> AsyncTestRunner.run(
                deadlockOnly().threads(2).invocations(1).useVirtualThreads(false).timeoutMs(6_000).build(),
                invertedMonitors(cleanRunStarted, bothHoldTheirFirst)));

        CompletableFuture<Throwable> cleanRun = runOnItsOwnThread(() -> AsyncTestRunner.run(
                deadlockOnly().threads(2).invocations(20).timeoutMs(10_000).build(), () -> {
                    cleanRunStarted.countDown();
                    Thread.sleep(40);
                }));
        assertTrue(bothHoldTheirFirst.await(10, TimeUnit.SECONDS),
                "precondition: the first run's workers deadlock while the clean run is going");
        Throwable clean = cleanRun.get(30, TimeUnit.SECONDS);
        assertNull(clean, "the other run deadlocked while this one was going, and none of this run's "
                + "threads is in the cycle: " + clean);

        Throwable own = deadlocking.get(30, TimeUnit.SECONDS);
        assertNotNull(own, "the deadlocking run must fail");
        assertTrue(String.valueOf(own.getMessage()).contains("DeadlockDetector"),
                "and name its own deadlock: " + own.getMessage());
    }

    /**
     * #1384. Fails if a ReentrantLock deadlock between virtual workers, the default thread kind,
     * draws no DeadlockDetector finding. Needs a JDK whose JSON thread dump names a parked thread's
     * lock owner, 25 and later; on 21 the dump has no parkBlocker and the runner says the detector
     * cannot see virtual-thread deadlocks.
     */
    @Test
    @EnabledIf("dumpNamesLockOwners")
    void aLockDeadlockBetweenVirtualWorkersIsReported() {
        AssertionError timeout = assertInstanceOf(AssertionError.class,
                runOnItsOwnThread(() -> AsyncTestRunner.run(
                        deadlockOnly().threads(2).invocations(1).timeoutMs(3_000).build(),
                        invertedLocks())).join());

        assertTrue(timeout.getMessage().contains("DeadlockDetector"),
                "the two virtual workers are parked on each other's lock: " + timeout.getMessage());
    }

    /** The twin: the same locks taken in one order. Fails if consistent ordering is reported. */
    @Test
    void consistentlyOrderedLocksOnVirtualWorkersAreSilent() {
        Lock a = new ReentrantLock();
        Lock b = new ReentrantLock();

        assertDoesNotThrow(() -> AsyncTestRunner.run(
                deadlockOnly().threads(4).invocations(20).timeoutMs(10_000).build(), () -> {
                    a.lock();
                    try {
                        b.lock();
                        b.unlock();
                    } finally {
                        a.unlock();
                    }
                }));
    }

    static boolean dumpNamesLockOwners() {
        return Runtime.version().feature() >= 25;
    }

    /** Two workers taking two monitors in opposite orders once {@code go} opens. */
    private static AsyncTestRunner.Body invertedMonitors(CountDownLatch go, CountDownLatch bothHoldTheirFirst) {
        Object a = new Object();
        Object b = new Object();
        AtomicInteger arrivals = new AtomicInteger();
        return () -> {
            go.await();
            boolean first = arrivals.getAndIncrement() % 2 == 0;
            Object x = first ? a : b;
            Object y = first ? b : a;
            synchronized (x) {
                bothHoldTheirFirst.countDown();
                bothHoldTheirFirst.await();
                synchronized (y) {
                    arrivals.incrementAndGet();
                }
            }
        };
    }

    /** Two workers taking two {@code ReentrantLock}s in opposite orders, each waiting for the other. */
    private static AsyncTestRunner.Body invertedLocks() {
        CountDownLatch bothHoldTheirFirst = new CountDownLatch(2);
        Lock a = new ReentrantLock();
        Lock b = new ReentrantLock();
        AtomicInteger arrivals = new AtomicInteger();
        return () -> {
            boolean first = arrivals.getAndIncrement() % 2 == 0;
            Lock x = first ? a : b;
            Lock y = first ? b : a;
            x.lock();
            try {
                bothHoldTheirFirst.countDown();
                bothHoldTheirFirst.await();
                y.lock();
                y.unlock();
            } finally {
                x.unlock();
            }
        };
    }

    /** Runs a call on a platform thread of its own and hands back what it threw, or null. */
    private static CompletableFuture<Throwable> runOnItsOwnThread(AsyncTestRunner.Body run) {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread caller = new Thread(() -> {
            try {
                run.run();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        }, "deadlock-attribution-caller");
        caller.setDaemon(true);
        caller.start();
        return outcome;
    }
}
