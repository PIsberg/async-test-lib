package se.deversity.asynctest.spi;
import se.deversity.asynctest.E2E;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for third-party {@link Detector}s.
 *
 * <p>The SPI was published as the supported way to add a detector, but nothing on the
 * execution path ever built an SPI registry: a user-supplied detector was never
 * instantiated by a running {@code @AsyncTest}, never received {@code onTestStart()} /
 * {@code onTestEnd()}, and its violations reached neither the reports nor the
 * {@code failOn} gate. These tests pin the wiring that closes that gap.
 */
@E2E
class ExternalDetectorSpiWiringTest {

    @AfterEach
    void disarmExternalDetector() {
        ExternalTestDetector.disarm();
    }

    @Test
    void contextIgnoresExternalDetectorsThatAreNotEnabled() {
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());

        assertFalse(ctx.analyzeAllNamed().containsKey(ExternalTestDetector.NAME),
                "a factory whose isEnabledFor() is false must contribute nothing");
        assertEquals(0, ExternalTestDetector.starts());
    }

    @Test
    void buildExternalSkipsTheBuiltInBridgeFactories() {
        ExternalTestDetector.arm();
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertEquals(1, DetectorRegistry.buildExternal(cfg).all().size(),
                "only the third-party factory may be built; the built-in bridges would be "
                        + "~120 duplicate detectors that observe nothing");
        assertTrue(DetectorRegistry.build(cfg).all().size() > 100,
                "the unfiltered build() must still see every built-in factory");
    }

    @Test
    void externalFindingsAreReportedWithTheirOwnSeverity() {
        ExternalTestDetector.arm();
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder().build());

        assertEquals(1, ExternalTestDetector.starts(),
                "onTestStart() fires once per context, before the first round");

        Map<String, String> reports = ctx.analyzeAllNamed();
        String report = reports.get(ExternalTestDetector.NAME);

        assertNotNull(report, "the violation must be keyed by Violation.detector()");
        assertTrue(report.contains(ExternalTestDetector.MESSAGE));
        assertEquals(IssueSeverity.MEDIUM, IssueSeverity.fromReport(report),
                "the gate classifies from report text, so the severity label must survive");
        assertTrue(ctx.analyzeAll().contains(report),
                "the free-text view must agree with the keyed one");
    }

    @Test
    void onTestEndFiresExactlyOncePerContext() {
        ExternalTestDetector.arm();
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder().build());

        ctx.analyzeAllNamed();
        assertEquals(1, ExternalTestDetector.ends());

        ctx.analyzeAllNamed();
        ctx.analyzeAll();
        assertEquals(1, ExternalTestDetector.ends(),
                "repeat analysis must not re-fire the end-of-test hook");
    }

    @Test
    void externalFindingTripsTheFailOnGate() {
        ExternalTestDetector.arm();
        try {
            Events tests = EngineTestKit.engine("junit-jupiter")
                    .selectors(DiscoverySelectors.selectClass(ExternalDetectorFixture.class))
                    .execute()
                    .testEvents();

            tests.assertStatistics(s -> s.started(1).succeeded(0).failed(1));
            assertTrue(ExternalTestDetector.starts() >= 1,
                    "the running test must have started the external detector");
            assertTrue(ExternalTestDetector.ends() >= 1,
                    "the running test must have ended the external detector");
        } finally {
            ExternalTestDetector.disarm();
        }
    }

    /** Fixture driven through junit-platform-testkit; its body passes, the SPI finding fails it. */
    static class ExternalDetectorFixture {

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 10_000,
                failOn = FailOn.MEDIUM, licenseMockMode = true)
        void passingBodyWithAnExternalFinding() {
            // Intentionally empty: the finding comes from the SPI detector, not the body.
        }
    }
}
