package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

@E2E
class DetectAllIntegrationTest {

    @Test
    void testDetectAllEnablesRaceConditionDetection() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(RaceTestWithDetectAll.class))
            .execute()
            .testEvents();

        // This is a bit tricky to verify via TestKit without checking logs, 
        // but we can check if it runs successfully and we'll manually verify the logic in a bit.
        assertTrue(events.failed().count() > 0,
            "an unsynchronized write recorded from two threads must fail the test");
    }

    @Test
    void testExcludesWorksWithDetectAll() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(RaceTestWithExcludes.class))
            .execute()
            .testEvents();

        assertTrue(events.succeeded().count() > 0);
    }

    public static class RaceTestWithDetectAll {
        private int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true, failOn = FailOn.HIGH)
        void race() {
            // The library sees field access through instrumentation, not by magic: a bare
            // `counter++` is invisible to RaceConditionDetector. Until this recorded the write,
            // this test asserted "detectAll enables race condition detection" while detecting no
            // race at all — it was green only because LivelockDetector was falsely reporting the
            // JVM's own idle daemon threads as starved. Removing that false positive exposed it.
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }

    public static class RaceTestWithExcludes {
        private volatile int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true, excludes = {DetectorType.RACE_CONDITIONS, DetectorType.LIVELOCKS}, failOn = FailOn.HIGH)
        void race() {
            counter++;
            assertTrue(counter >= 0); // Use counter to satisfy lint
        }
    }
}
