package com.github.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Verifies Fix 2: latch.await() in ConcurrencyRunner now has a timeout.
 *
 * Previously a thread stuck before latch.countDown() would hang the entire suite
 * forever. The runner now throws an AssertionError after (timeoutMs + 5s), so the
 * test fails quickly and the error message shows how many threads completed.
 */
class LatchTimeoutTest {

    @Test
    void hungThreadCausesAssertionErrorNotInfiniteHang() {
        long start = System.currentTimeMillis();

        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(HungThreadDummy.class))
            .execute()
            .testEvents();

        long elapsed = System.currentTimeMillis() - start;

        // Must fail, not hang
        assertEquals(1, events.failed().count(),
            "Hung-thread test must fail with an AssertionError");

        // Must complete well within 10 s (timeoutMs=300 + 5s slack + overhead)
        assertTrue(elapsed < 10_000,
            "Test took " + elapsed + "ms — likely hung instead of timing out");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Dummy test: one thread spins so it never reaches latch.countDown().
     * Very short timeoutMs so the test suite finishes quickly.
     */
    static class HungThreadDummy {
        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 300, detectDeadlocks = false)
        void oneThreadHangsForever() throws InterruptedException {
            if (Thread.currentThread().getId() % 2 != 0) {
                // spin — this thread never calls latch.countDown()
                Thread.sleep(60_000);
            }
        }
    }
}
