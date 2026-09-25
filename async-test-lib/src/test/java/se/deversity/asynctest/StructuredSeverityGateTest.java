package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the {@code failOn} gate reads the severity a detector put in its structured findings
 * before it guesses one from the report text.
 *
 * <p><strong>Why this exists.</strong> {@code ThreadLocalCacheDegradationDetector} rates every
 * finding {@code MEDIUM} in its {@link se.deversity.asynctest.report.Violation}, but its report text
 * carries no severity marker and it has no entry in {@code DetectorDefaultSeverity}. The gate only
 * read the text, found nothing, and fell back to {@code HIGH}: a ThreadLocal that stopped caching
 * on virtual threads failed a {@code failOn = HIGH} build as if it had proved data corruption.
 * The pair below is the same finding under the thresholds either side of {@code MEDIUM}.
 */
@E2E
class StructuredSeverityGateTest {

    @Test
    @DisplayName("a finding rated MEDIUM in its Violation does not fail a HIGH gate")
    void structuredMediumDoesNotTripAHighGate() {
        run(DegradedCacheUnderHighGateDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    @Test
    @DisplayName("the same finding fails a MEDIUM gate")
    void structuredMediumTripsAMediumGate() {
        Events tests = run(DegradedCacheUnderMediumGateDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("ThreadLocalCacheDegradationDetector")),
                "the failure must be the failOn gate naming the detector, not a fixture assertion "
                        + "or a timeout. Failures seen: " + messages);
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /** One formatter per virtual thread, so the ThreadLocal caches nothing; gated at HIGH. */
    public static class DegradedCacheUnderHighGateDummy {
        private final ThreadLocal<StringBuilder> buffer = ThreadLocal.withInitial(StringBuilder::new);

        @AsyncTest(threads = 4, invocations = 2, failOn = FailOn.HIGH, useVirtualThreads = true,
                   includes = {DetectorType.THREAD_LOCAL_CACHE_DEGRADATION}, licenseMockMode = true)
        void perTaskBuffer() {
            AsyncTestContext.threadLocalCacheDegradationDetector()
                    .recordCachedValue("BUFFER", buffer.get(), Thread.currentThread());
        }
    }

    /** The same degraded cache, gated at MEDIUM. */
    public static class DegradedCacheUnderMediumGateDummy {
        private final ThreadLocal<StringBuilder> buffer = ThreadLocal.withInitial(StringBuilder::new);

        @AsyncTest(threads = 4, invocations = 2, failOn = FailOn.MEDIUM, useVirtualThreads = true,
                   includes = {DetectorType.THREAD_LOCAL_CACHE_DEGRADATION}, licenseMockMode = true)
        void perTaskBuffer() {
            AsyncTestContext.threadLocalCacheDegradationDetector()
                    .recordCachedValue("BUFFER", buffer.get(), Thread.currentThread());
        }
    }
}
