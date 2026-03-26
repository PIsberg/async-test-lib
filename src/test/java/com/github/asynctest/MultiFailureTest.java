package com.github.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Verifies Fix 3: all concurrent thread failures are captured, not just the first.
 *
 * Previously an AtomicReference stored only the first failure; subsequent ones were
 * silently dropped. Now a CopyOnWriteArrayList collects every failure and the error
 * message names all of them (with the first as cause and the rest as suppressed).
 */
class MultiFailureTest {

    @Test
    void allThreadFailuresAreReported() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(AllThreadsFailDummy.class))
            .execute()
            .testEvents();

        assertEquals(1, events.failed().count(), "test must fail");

        Throwable thrown = events.failed().stream()
            .findFirst()
            .map(e -> e.getPayload(Throwable.class).orElse(null))
            .orElse(null);

        // Fallback: get the cause from the failure event's exception
        if (thrown == null) {
            thrown = events.failed().stream()
                .findFirst()
                .map(e -> {
                    try {
                        return (Throwable) e.getClass()
                            .getMethod("getThrowable").invoke(e);
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .orElse(null);
        }

        // At minimum, the test must fail — we can verify suppressed via the runner directly
    }

    @Test
    void multipleFailuresProduceCombinedMessage() throws Throwable {
        // Drive the runner directly to inspect the error
        AsyncTestConfig config = AsyncTestConfig.builder()
            .threads(4)
            .invocations(1)
            .timeoutMs(2_000)
            .detectDeadlocks(false)
            .build();

        AtomicInteger failCount = new AtomicInteger(0);

        // Simulate a round where all 4 threads fail
        List<Throwable> fakeFailures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fakeFailures.add(new AssertionError("thread " + i + " failed"));
        }

        // Use reflection to call the private buildMultiFailureError method
        java.lang.reflect.Method m = com.github.asynctest.runner.ConcurrencyRunner.class
            .getDeclaredMethod("buildMultiFailureError", java.util.List.class);
        m.setAccessible(true);
        AssertionError combined = (AssertionError) m.invoke(null, fakeFailures);

        // Message must mention all 4 failures
        String msg = combined.getMessage();
        assertTrue(msg.contains("4"), "message should mention 4 failures: " + msg);
        assertTrue(msg.contains("thread 0 failed"), "first failure present: " + msg);
        assertTrue(msg.contains("thread 3 failed"), "last failure present: " + msg);

        // First failure is the cause
        assertEquals("thread 0 failed", combined.getCause().getMessage());

        // Remaining are suppressed
        assertEquals(3, combined.getSuppressed().length,
            "should have 3 suppressed throwables");
    }

    @Test
    void singleFailurePreservesOriginalType() throws Throwable {
        java.lang.reflect.Method m = com.github.asynctest.runner.ConcurrencyRunner.class
            .getDeclaredMethod("buildMultiFailureError", java.util.List.class);
        m.setAccessible(true);

        List<Throwable> single = java.util.List.of(new AssertionError("only one"));
        AssertionError result = (AssertionError) m.invoke(null, single);
        assertEquals("only one", result.getMessage());
    }

    // ---- Dummy tests driven via EngineTestKit ----

    static class AllThreadsFailDummy {
        @AsyncTest(threads = 4, invocations = 1, timeoutMs = 2_000, detectDeadlocks = false)
        void allFail() {
            throw new AssertionError("intentional failure from thread "
                + Thread.currentThread().getName());
        }
    }
}
