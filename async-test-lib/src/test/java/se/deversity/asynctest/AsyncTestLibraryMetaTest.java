package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class AsyncTestLibraryMetaTest {

    @Test
    void testRaceConditionIsCaught() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(RaceConditionDummy.class))
                .execute()
                .testEvents();
                
        long failed = testEvents.failed().count();
        assertEquals(1, failed, "The unsynchronised counter should end below 20 x 100 and fail the "
                + "dummy's own @AfterEach. This asserts that the bug manifests under contention, not "
                + "that a detector reported it: failOn defaults to NONE, so no finding can fail "
                + "this run. DetectionCoverageTest is where detector reporting is asserted.");
    }

    public static class RaceConditionDummy {
        private int unprotectedCounter = 0;

        @AsyncTest(threads = 20, invocations = 100)
        void testCounterRace() {
            int current = unprotectedCounter;
            Thread.yield();
            unprotectedCounter = current + 1;
        }

        @AfterEach
        void verify() {
            assertEquals(20 * 100, unprotectedCounter, "Counter should be 2000 if thread-safe");
        }
    }

    @Test
    void testDeadlockIsCaught() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(DeadlockDummy.class))
                .execute()
                .testEvents();
                
        long failed = testEvents.failed().count();
        assertEquals(1, failed, "The circular lock dependency should stall both threads until the "
                + "round timeout and fail the run. DeadlockDetector also reports it, which "
                + "DetectionCoverageTest asserts; this test only pins that the run fails.");
    }

    public static class DeadlockDummy {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 1500, useVirtualThreads = false)
        void testDeadlock() throws InterruptedException {
            int id = threadAssigner.getAndIncrement();
            if (id % 2 == 0) {
                synchronized (lock1) {
                    Thread.sleep(100);
                    synchronized (lock2) { }
                }
            } else {
                synchronized (lock2) {
                    Thread.sleep(100);
                    synchronized (lock1) { }
                }
            }
        }
    }

    /**
     * Whether a non-volatile flag is observed stale depends on the JVM, the CPU and the JIT, so
     * whether this run fails is genuinely not decidable here. What is decidable is that the
     * template executed: this used to assert nothing at all (its only assertion was commented out),
     * which made it a test that would have passed with the library deleted. It now pins execution
     * and leaves the outcome unasserted, deliberately and visibly.
     *
     * <p>Detector reporting is asserted in {@code DetectionCoverageTest}, where the scenarios are
     * chosen to be deterministic.
     */
    @Test
    void visibilityDummyExecutes_thoughItsOutcomeIsNotDeterministic() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(VisibilityDummy.class))
                .execute()
                .testEvents();

        assertEquals(1, testEvents.started().count(),
                "The @AsyncTest template should have produced exactly one test execution. Zero "
                        + "means discovery or the extension broke, which is a real regression even "
                        + "though the visibility outcome itself is not decidable here.");
        assertEquals(0, testEvents.aborted().count(),
                "The run should finish rather than abort: an abort means an assumption or "
                        + "infrastructure failure, not the visibility bug this dummy exists for.");

        long failed = testEvents.failed().count();
        if (failed == 0) {
            System.err.println("[meta] Visibility dummy passed this time. Non-deterministic by "
                    + "nature: the JVM may have flushed the write. Not a failure of the library.");
        }
    }

    public static class VisibilityDummy {
        private boolean stopHolder = false;
        private final AtomicInteger assigner = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 5, timeoutMs = 15000, useVirtualThreads = false)
        void testVisibility() throws Exception {
            if (assigner.getAndIncrement() % 2 == 0) {
                stopHolder = true;
            } else {
                // Tight loop without volatile - JIT should optimize to infinite loop
                while (!stopHolder) {
                    // Do nothing - just spin
                }
            }
        }
    }

    @Test
    void testVirtualThreadStress() {
        Assumptions.assumeTrue(se.deversity.asynctest.diagnostics.VirtualThreadStressConfig.isVirtualThreadSupported());
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(VirtualThreadStressDummy.class))
                .execute()
                .testEvents();

        assertEquals(0, testEvents.aborted().count(),
            "Virtual thread execution should complete without aborting.");
    }

    public static class VirtualThreadStressDummy {
        @AsyncTest(threads = 250, invocations = 2, useVirtualThreads = true, timeoutMs = 45000)
        void stress() throws InterruptedException {
            Thread.sleep(5);
        }
    }
}
