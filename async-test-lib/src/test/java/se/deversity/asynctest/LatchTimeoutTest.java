package se.deversity.asynctest;

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
@E2E
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

        // Must complete in bounded time (timeoutMs=300 + 5s latch slack + engine
        // overhead). The generous cap only guards against an infinite hang — it must
        // not trip on a slow, heavily loaded CI runner.
        assertTrue(elapsed < 20_000,
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
        // Role assignment must be deterministic: thread-id parity is NOT — the two
        // worker threads can both get even (or both odd) ids when other JVM threads
        // claim ids in between, and with no hung thread the dummy would pass.
        private final java.util.concurrent.atomic.AtomicInteger role =
            new java.util.concurrent.atomic.AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 300, detectDeadlocks = false,
                   useVirtualThreads = false)
        void oneThreadHangsForever() throws InterruptedException {
            if (role.getAndIncrement() == 0) {
                // this thread never reaches latch.countDown()
                Thread.sleep(60_000);
            }
        }
    }
}
