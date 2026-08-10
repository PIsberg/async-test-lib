package se.deversity.asynctest.fixture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncAssert;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer-side coverage of the 1.9.0 assertion surface: {@code AsyncFindings} and the
 * {@code AsyncAssert} additions.
 *
 * <p>Like the other files here it compiles against the built artifact rather than the source, so
 * a passing run proves a consumer can reach these symbols without touching internals — including
 * {@code report.Violation}, which the collector hands back.
 *
 * <p>The class-level collector is registered in {@code @BeforeAll} and asserted in
 * {@code @AfterAll} because detectors analyse after the last round: a finding cannot be observed
 * from inside the test body, and JUnit does not order {@code @Test} against {@code @AsyncTest}
 * methods.
 */
class ConsumerFindingsAssertionTest {

    private static AsyncFindings findings;

    private int counter;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void theRaceWasReported() {
        try {
            findings.assertReported("RaceConditionDetector");

            List<Violation> races = findings.violationsFrom("RaceConditionDetector");
            assertFalse(races.isEmpty());
            Violation race = races.get(0);
            assertNotNull(race.severity());
            assertFalse(race.message().isBlank());
            assertTrue(race.attributes().containsKey("report"),
                    "The full report text must stay reachable from the violation");
        } finally {
            findings.close();
        }
    }

    // ---- 1) A real run leaves assertable findings behind ----

    @AsyncTest(threads = 4, invocations = 10, failOn = FailOn.NONE, licenseMockMode = true)
    void unsynchronised_increment_is_reported_rather_than_thrown() {
        AsyncTestContext ctx = AsyncTestContext.get();
        if (ctx != null) {
            ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
        }
        counter++;
    }

    // ---- 2) The collector's assertions, driven directly ----

    @Test
    void collector_assertions_are_reachable_and_report_what_was_seen() {
        try (AsyncFindings scoped = AsyncFindings.collect()) {
            scoped.assertNone();

            AsyncTestListenerRegistry.fireDetectorReport(
                    "BusyWaitDetector", "🟡 MEDIUM: spin loop with no backoff");

            scoped.assertReported("BusyWaitDetector");
            scoped.assertReported("BusyWait", IssueSeverity.MEDIUM);
            scoped.assertNotReported("DeadlockDetector");
            assertThrows(AssertionError.class, scoped::assertNone);
            assertEquals(1, scoped.violations().size());

            scoped.clear();
            scoped.assertNone();
        }
    }

    // ---- 3) AsyncAssert additions ----

    @Test
    void awaitUntil_names_the_wait_and_carries_the_last_exception() {
        IllegalStateException boom = new IllegalStateException("not ready");

        AssertionError error = assertThrows(AssertionError.class, () ->
                AsyncAssert.awaitUntil(() -> { throw boom; }, Duration.ofMillis(30), "cache warmed"));

        assertTrue(error.getMessage().contains("cache warmed"), error.getMessage());
        assertSame(boom, error.getCause());
    }

    @Test
    void awaitUntil_evaluates_the_condition_even_with_a_zero_timeout() {
        AsyncAssert.awaitUntil(() -> true, Duration.ZERO, "already true");
    }

    @Test
    void futureCapture_separates_running_failed_and_null() {
        CompletionStage<String> stage = CompletableFuture.completedFuture("ok");
        AsyncAssert.FutureCapture<String> ok = AsyncAssert.capture(stage);
        ok.awaitDone(Duration.ofSeconds(2));
        assertTrue(ok.isSuccess());
        assertEquals("ok", ok.requireResult());

        CompletableFuture<String> failed = new CompletableFuture<>();
        IllegalStateException boom = new IllegalStateException("upstream died");
        failed.completeExceptionally(boom);
        AsyncAssert.FutureCapture<String> broken = AsyncAssert.capture(failed);
        broken.awaitDone(Duration.ofSeconds(2));
        assertTrue(broken.isFailed());
        assertSame(boom, assertThrows(AssertionError.class, broken::requireResult).getCause());

        AsyncAssert.FutureCapture<String> running = AsyncAssert.capture(new CompletableFuture<String>());
        assertFalse(running.isSuccess());
        assertFalse(running.isFailed());
        assertThrows(AssertionError.class, running::requireResult);
    }
}
