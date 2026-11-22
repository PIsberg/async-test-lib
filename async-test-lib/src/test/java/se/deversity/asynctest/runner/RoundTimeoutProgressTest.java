package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.Preset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner tells a budget that ran out while rounds were completing from a round that hung
 * (#1386). {@link RoundTimeoutMessageTest} pins the texts; this pins which one a real run gets.
 */
@E2E
class RoundTimeoutProgressTest {

    @Test
    void aSlowCorrectBodyThatRunsOutOfBudgetIsNotCalledADeadlock() {
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(40).timeoutMs(1000).build(),
                        () -> Thread.sleep(40))));

        String message = timeout.getMessage();
        assertTrue(message.contains("rounds completed"), message);
        assertTrue(message.contains("raise it or lower invocations"), message);
        assertFalse(message.contains("Possible deadlock"), message);
        if (timeout.getCause() != null) {
            assertFalse(String.valueOf(timeout.getCause().getMessage()).contains("barrier"),
                    "the workers were in the body: " + timeout.getCause().getMessage());
        }
    }

    @Test
    void aHungRoundIsStillAPossibleDeadlock() {
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(1).timeoutMs(300).build(),
                        () -> Thread.sleep(30_000))));

        assertTrue(timeout.getMessage().contains("Possible deadlock"), timeout.getMessage());
        assertFalse(String.valueOf(timeout.getCause().getMessage()).contains("barrier"),
                "both workers were asleep in the body: " + timeout.getCause().getMessage());
        assertFalse(timeout.getMessage().contains("blocked or waiting with no timeout"),
                "a sleeping worker is TIMED_WAITING and still going somewhere: " + timeout.getMessage());
    }

    @Test
    void aRoundWhoseWorkersAllWaitWithNoTimeoutSaysSo() {
        // Both workers park on a latch nobody opens: WAITING with no timeout, which no budget
        // would have outlasted (#1393). The 30-second sleep above is the twin that must not say so.
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(1).timeoutMs(300).build(),
                        never::await)));

        assertTrue(timeout.getMessage().contains(
                "every worker still in the body was blocked or waiting with no timeout"), timeout.getMessage());
    }

    @Test
    void aRoundThatHangsLateWithLittleBudgetLeftIsStillAPossibleDeadlock() {
        // Correct 40 ms rounds until most of the budget is spent, then the two workers take two
        // locks in opposite orders. The hung round starts with under four rounds' worth of budget
        // left, which the timing rule alone reads as running out (#1393); both workers are parked
        // on a lock when it ends, which says otherwise.
        long[] started = {0L};
        java.util.concurrent.locks.ReentrantLock first = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.ReentrantLock second = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.atomic.AtomicInteger arrivals = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CyclicBarrier bothHold = new java.util.concurrent.CyclicBarrier(2);
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(40).timeoutMs(1000).build(), () -> {
                    synchronized (started) {
                        if (started[0] == 0L) {
                            started[0] = System.nanoTime();
                        }
                    }
                    if (System.nanoTime() - started[0] < 850_000_000L) {
                        Thread.sleep(40);
                        return;
                    }
                    boolean even = arrivals.getAndIncrement() % 2 == 0;
                    java.util.concurrent.locks.ReentrantLock mine = even ? first : second;
                    java.util.concurrent.locks.ReentrantLock theirs = even ? second : first;
                    mine.lockInterruptibly();
                    try {
                        bothHold.await();
                        theirs.lockInterruptibly();
                        theirs.unlock();
                    } finally {
                        mine.unlock();
                    }
                })));

        assertTrue(timeout.getMessage().contains("Possible deadlock"), timeout.getMessage());
        assertFalse(timeout.getMessage().contains("raise it or lower invocations"), timeout.getMessage());
    }

    private static AsyncTestConfig.Builder config() {
        return AsyncTestConfig.builder().licenseMockMode(true).preset(Preset.NONE);
    }

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    private String previousMultiplier;

    @BeforeEach
    void rememberTheMultiplier() {
        previousMultiplier = System.getProperty(MULTIPLIER_PROPERTY);
    }

    @AfterEach
    void restoreTheMultiplier() {
        if (previousMultiplier == null) {
            System.clearProperty(MULTIPLIER_PROPERTY);
        } else {
            System.setProperty(MULTIPLIER_PROPERTY, previousMultiplier);
        }
    }

    /** CI stretches every budget; both runs depend on theirs expiring when stated. */
    private static void withUnstretchedBudget(AsyncTestRunner.Body run) throws Throwable {
        System.setProperty(MULTIPLIER_PROPERTY, "1.0");
        run.run();
    }
}
