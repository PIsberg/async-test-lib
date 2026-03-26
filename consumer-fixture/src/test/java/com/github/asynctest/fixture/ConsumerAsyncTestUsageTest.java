package com.github.asynctest.fixture;

import com.github.asynctest.AfterEachInvocation;
import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.BeforeEachInvocation;
import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConsumerAsyncTestUsageTest {

    private final AtomicInteger roundCounter = new AtomicInteger();
    private final AtomicInteger beforeRounds = new AtomicInteger();
    private final AtomicInteger afterRounds = new AtomicInteger();
    private final AtomicInteger totalExecutions = new AtomicInteger();
    private final AtomicReference<Object> sharedDetector = new AtomicReference<>();

    @BeforeEachInvocation
    void resetRoundState() {
        roundCounter.set(0);
        beforeRounds.incrementAndGet();
    }

    @AsyncTest(
        threads = 3,
        invocations = 4,
        detectFalseSharing = true,
        timeoutMs = 5_000,
        detectDeadlocks = false
    )
    void asyncTestWorksFromPublishedDependency() {
        totalExecutions.incrementAndGet();
        roundCounter.incrementAndGet();

        assertNotNull(AsyncTestContext.get());
        Object detector = AsyncTestContext.falseSharingDetector();
        assertNotNull(detector);

        Object initial = sharedDetector.updateAndGet(existing -> existing == null ? detector : existing);
        assertSame(initial, detector, "all threads and rounds must see the same shared detector instance");
    }

    @AfterEachInvocation
    void verifyPerRoundLifecycle() {
        afterRounds.incrementAndGet();
        assertEquals(3, roundCounter.get(), "one invocation round must execute exactly once per configured thread");
    }

    @AfterEach
    void verifyWholeTestLifecycle() {
        assertEquals(4, beforeRounds.get(), "@BeforeEachInvocation must run once per round");
        assertEquals(4, afterRounds.get(), "@AfterEachInvocation must run once per round");
        assertEquals(12, totalExecutions.get(), "@AsyncTest must execute threads x invocations times");
    }
}
