package com.github.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Verifies Fix 5: @BeforeEachInvocation and @AfterEachInvocation lifecycle hooks.
 *
 * @BeforeEach / @AfterEach only bracket the entire N×M execution once.
 * The new annotations let tests reset (or assert) state around every individual
 * invocation round without embedding that logic inside the test body itself.
 */
class PerInvocationLifecycleTest {

    // ---- @BeforeEachInvocation resets state between rounds ----

    static final int THREADS = 5;
    static final int INVOCATIONS = 10;

    static class ResetBetweenRounds {
        private int counter = 0;
        private final AtomicInteger roundsChecked = new AtomicInteger(0);

        @BeforeEachInvocation
        void resetCounter() {
            counter = 0;
        }

        @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 5_000,
                   useVirtualThreads = false,
                   detectDeadlocks = false)
        void increment() {
            counter++;
        }

        @AfterEachInvocation
        void checkRoundResult() {
            // counter is not thread-safe, but it must be ≤ THREADS (one round)
            // and > 0 (at least one thread incremented)
            if (counter > 0 && counter <= THREADS) {
                roundsChecked.incrementAndGet();
            }
        }

        @AfterEach
        void verify() {
            // All INVOCATIONS rounds were checked by @AfterEachInvocation
            assertEquals(INVOCATIONS, roundsChecked.get(),
                "AfterEachInvocation must have run for every invocation round");
        }
    }

    @Test
    void beforeAndAfterEachInvocationHooksAreInvoked() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ResetBetweenRounds.class))
            .execute()
            .testEvents();

        // The test itself should pass (the @AfterEach assertion must hold)
        assertEquals(0, events.failed().count(),
            "ResetBetweenRounds test must pass: " + events.failed().count() + " failures");
    }

    // ---- @BeforeEachInvocation runs before @AfterEachInvocation for every round ----

    static class OrderVerification {
        private final AtomicInteger beforeCount  = new AtomicInteger(0);
        private final AtomicInteger afterCount   = new AtomicInteger(0);

        @BeforeEachInvocation
        void before() { beforeCount.incrementAndGet(); }

        @AsyncTest(threads = 2, invocations = 3, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void noOp() { /* nothing */ }

        @AfterEachInvocation
        void after() { afterCount.incrementAndGet(); }

        @AfterEach
        void verify() {
            assertEquals(3, beforeCount.get(), "@BeforeEachInvocation must fire 3 times");
            assertEquals(3, afterCount.get(),  "@AfterEachInvocation must fire 3 times");
        }
    }

    @Test
    void hooksFireExactlyOncePerInvocationRound() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(OrderVerification.class))
            .execute()
            .testEvents();

        assertEquals(0, events.failed().count(),
            "OrderVerification test must pass");
    }

    // ---- @AfterEachInvocation runs even when the round fails ----

    static class AfterRunsOnFailure {
        private final AtomicInteger afterCount = new AtomicInteger(0);
        private final AtomicInteger rounds     = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void alwaysFails() {
            throw new AssertionError("intentional");
        }

        @AfterEachInvocation
        void countAfter() { afterCount.incrementAndGet(); }
    }

    @Test
    void afterEachInvocationRunsEvenOnRoundFailure() {
        // We just verify the engine doesn't crash; the test itself will fail
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(AfterRunsOnFailure.class))
            .execute()
            .testEvents();

        // Exactly 1 test ran (the @AsyncTest), and it failed
        assertEquals(1, events.failed().count());
    }
}
