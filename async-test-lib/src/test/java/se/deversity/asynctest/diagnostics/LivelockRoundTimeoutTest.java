package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * What {@link LivelockDetector} says about a whole run, driven the way a user's test drives it.
 *
 * <p>The runner used to sample every worker from each worker's own {@code finally}. A worker saw
 * itself RUNNABLE there and its finished peers WAITING in the pool's queue, so a healthy platform
 * run alternated between the two and read as rapid state cycling, at CRITICAL, on a body that only
 * increments a counter. Nothing in those samples could tell a livelock from a test that finished.
 *
 * <p>The one observation that can is that the body did not finish: a round that timed out, with a
 * worker still in its body. These tests hold both directions, on both kinds of worker.
 */
@E2E
class LivelockRoundTimeoutTest {

    /**
     * Long enough for the workers to reach their bodies. The budget starts before the run's detectors
     * are built, so at 300 ms a loaded runner timed out with nobody in a body and nothing to classify.
     */
    private static final int ROUND_TIMEOUT_MS = 2_000;

    private final List<String> findings = new CopyOnWriteArrayList<>();

    private final AsyncTestListener listener = new AsyncTestListener() {
        @Override
        public void onDetectorReport(String detectorName, String report) {
            if ("LivelockDetector".equals(detectorName)) {
                findings.add(report);
            }
        }
    };

    @BeforeEach
    void listen() {
        AsyncTestListenerRegistry.register(listener);
    }

    @AfterEach
    void stopListening() {
        AsyncTestListenerRegistry.unregister(listener);
    }

    private void run(Class<?> dummy, int expectedSucceeded, int expectedFailed) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(dummy))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(expectedSucceeded).failed(expectedFailed));
    }

    @Test
    @DisplayName("healthy bodies on platform workers draw nothing, however unevenly they finish")
    void healthyPlatformWorkersAreNotReported() {
        run(HealthyPlatformBodies.class, 2, 0);

        assertEquals(List.of(), findings,
                "these bodies finished every round, so nothing about them is a livelock");
    }

    @Test
    @DisplayName("a platform worker still spinning when the round times out is a livelock candidate")
    void aSpinningPlatformWorkerIsACandidate() {
        run(SpinningPlatformBody.class, 0, 1);

        assertReports("Livelock Candidates");
    }

    @Test
    @DisplayName("a virtual worker still spinning when the round times out is a candidate too")
    void aSpinningVirtualWorkerIsACandidate() {
        run(SpinningVirtualBody.class, 0, 1);

        assertReports("Livelock Candidates");
    }

    @Test
    @DisplayName("a worker parked on a latch nobody counts down, when the round times out, is starved")
    void aParkedWorkerIsStarved() {
        run(ParkedBody.class, 0, 1);

        assertReports("Starved Threads");
    }

    private void assertReports(String expected) {
        assertTrue(findings.stream().anyMatch(report -> report.contains(expected)),
                "expected a LivelockDetector finding naming '" + expected + "', got: " + findings);
    }

    /** The two shapes the old samples read as cycling: tiny bodies, and uneven finishing times. */
    static class HealthyPlatformBodies {
        private static final AtomicInteger COUNTER = new AtomicInteger();

        @AsyncTest(threads = 6, invocations = 40, useVirtualThreads = false, detectAll = true)
        void tinyBody() {
            COUNTER.incrementAndGet();
        }

        @AsyncTest(threads = 4, invocations = 30, useVirtualThreads = false, detectAll = true)
        void unevenFinish() {
            LockSupport.parkNanos((COUNTER.incrementAndGet() % 4) * 1_000_000L);
        }
    }

    /** Runs until the runner interrupts it, burning CPU, which is what a spin-retry never ends in. */
    private static void spinUntilInterrupted() {
        while (!Thread.currentThread().isInterrupted()) {
            Thread.onSpinWait();
        }
    }

    static class SpinningPlatformBody {
        @AsyncTest(threads = 2, invocations = 1, useVirtualThreads = false, detectAll = true, timeoutMs = ROUND_TIMEOUT_MS)
        void spins() {
            spinUntilInterrupted();
        }
    }

    static class SpinningVirtualBody {
        @AsyncTest(threads = 2, invocations = 1, useVirtualThreads = true, detectAll = true, timeoutMs = ROUND_TIMEOUT_MS)
        void spins() {
            spinUntilInterrupted();
        }
    }

    static class ParkedBody {
        private static final CountDownLatch NEVER = new CountDownLatch(1);

        @AsyncTest(threads = 2, invocations = 1, useVirtualThreads = false, detectAll = true, timeoutMs = ROUND_TIMEOUT_MS)
        void parks() throws InterruptedException {
            NEVER.await();
        }
    }
}
