package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Asserts which detectors actually report, for real buggy code, through a real {@code @AsyncTest}
 * run.
 *
 * <p><strong>Why this exists.</strong> The suite had two kinds of detection test and neither
 * asserted detection. The 146 per-detector unit tests hand a detector records and check the report
 * it computes, which proves the analyser and says nothing about whether anything feeds it. The
 * meta-tests run genuinely buggy code under {@code @AsyncTest} and assert the run failed, but they
 * use the default {@code failOn = NONE}, and {@link FailOn#triggeredBy} returns {@code false}
 * unconditionally for {@code NONE}, so a detector finding can never fail those runs. The failure
 * they observe is the dummy's own {@code @AfterEach} assertion. Every one of them would still pass
 * with all 127 detectors switched off.
 *
 * <p>So nothing connected the two halves: config to registry to context to recording to analysis to
 * the report a user actually sees. That is exactly the seam where the agent's telemetry turned out
 * to be going nowhere. These tests watch the reporting channel itself, through
 * {@link AsyncTestListener#onDetectorReport}, which is the same channel the printed report and the
 * {@code failOn} gate are built on.
 *
 * <p>The last test pins a limitation rather than a capability, on purpose. It records what a bare
 * {@code @AsyncTest} does <em>not</em> catch, so the gap is written down and checked instead of
 * being assumed away. If it ever fails because the finding now appears, that is good news and the
 * test should become a positive assertion.
 */
@E2E
class DetectionCoverageTest {

    @Test
    @DisplayName("a real deadlock is reported by DeadlockDetector, with no instrumentation")
    void deadlockIsReportedWithoutAnyInstrumentation() {
        Set<String> reported = detectorsReportedBy(DeadlockingDummy.class);

        assertTrue(reported.contains("DeadlockDetector"),
                "DeadlockDetector reads the JVM's own thread state through ThreadMXBean, so it is "
                        + "one of the few detectors that needs neither the agent nor a recording "
                        + "call. If it stopped reporting a genuine circular lock dependency, the "
                        + "library's only zero-configuration detection would be gone. Reported: "
                        + reported);
    }

    @Test
    @DisplayName("a race recorded through the public API is reported by RaceConditionDetector")
    void raceRecordedThroughThePublicApiIsReported() {
        Set<String> reported = detectorsReportedBy(InstrumentedRaceDummy.class);

        assertTrue(reported.contains("RaceConditionDetector"),
                "This is the documented path for a detector that needs data about the code under "
                        + "test: the body calls AsyncTestContext.raceConditionDetector() itself. It "
                        + "exercises the whole chain (annotation, config, registry, per-thread "
                        + "context, recording, analysis, report) rather than a detector in "
                        + "isolation. Reported: " + reported);
    }

    @Test
    @DisplayName("an uninstrumented race is not reported, which is the gap the agent exists to close")
    void uninstrumentedRaceIsNotReported() {
        Set<String> reported = detectorsReportedBy(BareRaceDummy.class);

        assertFalse(reported.contains("RaceConditionDetector"),
                "A bare @AsyncTest on an unsynchronised counter has nothing feeding "
                        + "RaceConditionDetector: no recording call in the body, and the agent is "
                        + "not attached here. If this now reports, something started supplying the "
                        + "data. Turn this into a positive assertion and say so in AGENT.md, "
                        + "because it changes what a bare @AsyncTest is worth. Reported: "
                        + reported);
    }

    /**
     * Runs {@code dummy} through the engine and returns the names of the detectors that reported a
     * finding, taken from the listener channel the printed report and the {@code failOn} gate share.
     */
    private static Set<String> detectorsReportedBy(Class<?> dummy) {
        List<String> names = new CopyOnWriteArrayList<>();
        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                names.add(detectorName);
            }
        };
        AsyncTestListenerRegistry.register(listener);
        try {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(dummy)).execute();
        } finally {
            AsyncTestListenerRegistry.unregister(listener);
        }
        return Set.copyOf(names);
    }

    /**
     * Two threads taking the same two locks in opposite orders, each holding its first lock long
     * enough for the other to take theirs. Modelled on {@code AsyncTestLibraryMetaTest.DeadlockDummy},
     * which is the shape ThreadMXBean reliably reports as a circular dependency; platform threads
     * because a virtual thread parked on a monitor is not what findDeadlockedThreads looks for.
     */
    public static class DeadlockingDummy {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private final AtomicInteger threadAssigner = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 1500, useVirtualThreads = false)
        void deadlock() throws InterruptedException {
            if (threadAssigner.getAndIncrement() % 2 == 0) {
                synchronized (lock1) {
                    Thread.sleep(100);
                    synchronized (lock2) { /* unreachable while the peer holds lock2 */ }
                }
            } else {
                synchronized (lock2) {
                    Thread.sleep(100);
                    synchronized (lock1) { /* unreachable while the peer holds lock1 */ }
                }
            }
        }
    }

    /** A shared counter whose accesses are recorded through the public detector API. */
    public static class InstrumentedRaceDummy {
        private final Counter counter = new Counter();

        @AsyncTest(threads = 4, invocations = 5, includes = DetectorType.RACE_CONDITIONS)
        void racyIncrement() {
            AsyncTestContext.raceConditionDetector().recordFieldRead(counter, "value");
            int current = counter.value;
            Thread.yield();
            counter.value = current + 1;
            AsyncTestContext.raceConditionDetector().recordFieldWrite(counter, "value");
        }

        static final class Counter {
            int value;
        }
    }

    /** The same race with nothing recording it: what a user gets from a bare {@code @AsyncTest}. */
    public static class BareRaceDummy {
        private int counter;

        @AsyncTest(threads = 4, invocations = 5, includes = DetectorType.RACE_CONDITIONS)
        void racyIncrement() {
            int current = counter;
            Thread.yield();
            counter = current + 1;
        }
    }
}
