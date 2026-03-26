package com.github.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Verifies Fix 6: all threads in all rounds use the same method.invoke() code path.
 *
 * Previously thread 0 of round 0 called invocation.proceed() while all other
 * threads/rounds called method.invoke() directly — inconsistent within a round.
 * After the fix, invocation.skip() satisfies JUnit and every execution uses
 * method.invoke() uniformly. Consequences:
 *  - AsyncTestContext is installed before the first line of every execution.
 *  - Total execution count equals threads × invocations exactly.
 *  - No "extra" execution on the coordinator thread.
 */
class UniformInvocationPathTest {

    @Test
    void allExecutionsUseUniformPath() {
        UniformDummy.executionCount.set(0);
        UniformDummy.contextNullCount.set(0);

        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(UniformDummy.class))
            .execute()
            .testEvents();

        assertEquals(0, events.failed().count(),
            "UniformDummy must pass — all assertions inside the @AsyncTest must hold");

        int expected = UniformDummy.THREADS * UniformDummy.INVOCATIONS;
        assertEquals(expected, UniformDummy.executionCount.get(),
            "Total executions must equal threads × invocations");

        assertEquals(0, UniformDummy.contextNullCount.get(),
            "AsyncTestContext must be non-null in every single execution, not just thread 0/round 0");
    }

    // ---- Dummy class run via EngineTestKit ----

    static class UniformDummy {
        static final int THREADS     = 4;
        static final int INVOCATIONS = 5;

        // Static so they are shared across @AsyncTest and @AfterEach instances
        static final AtomicInteger executionCount  = new AtomicInteger(0);
        static final AtomicInteger contextNullCount = new AtomicInteger(0);

        @AsyncTest(threads = THREADS, invocations = INVOCATIONS,
                   useVirtualThreads = false,
                   detectFalseSharing = true, timeoutMs = 5_000, detectDeadlocks = false)
        void countExecutions() {
            executionCount.incrementAndGet();
            if (AsyncTestContext.get() == null) {
                contextNullCount.incrementAndGet();
            }
            // Phase 2 accessor must work from every thread
            assertNotNull(AsyncTestContext.falseSharingDetector());
        }
    }
}
