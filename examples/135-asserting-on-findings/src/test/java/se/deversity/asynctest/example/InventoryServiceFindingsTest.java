package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncAssert;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.example.service.InventoryService;
import se.deversity.asynctest.report.Violation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserting on what the detectors reported, rather than reading it in the build log.
 *
 * <p>Most examples here let the run fail and leave the report to a human. This one keeps the run
 * green with {@code failOn = FailOn.NONE} and asserts on the findings instead, which is what you
 * want when the finding <em>is</em> the expected outcome: a regression test for a known hazard, or
 * a test that a fix silenced a detector.
 *
 * <p>These tests run in CI. They are not {@code @Disabled}, because nothing here is expected to
 * fail: the race is reported, and the assertion checks that it was.
 */
class InventoryServiceFindingsTest {

    private static AsyncFindings findings;

    private final InventoryService service = new InventoryService(1_000);

    @BeforeAll
    static void collectFindings() {
        // The registry is JVM-wide, so a collector must be closed again. Registering here and
        // closing in @AfterAll scopes it to this class.
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void theRaceWasReported() {
        try {
            // Substring-matching a report written for humans is what this replaces.
            findings.assertReported("RaceConditionDetector");
            findings.assertNotReported("DeadlockDetector");

            List<Violation> races = findings.violationsFrom("RaceConditionDetector");
            Violation race = races.get(0);
            System.out.println("[example] " + race.detector() + " (" + race.severity() + "): "
                    + race.message());
            // The full report text is still there for anything the record does not carry.
            assertTrue(race.attributes().containsKey("report"));
        } finally {
            findings.close();
        }
    }

    /**
     * The run itself. {@code failOn = FailOn.NONE} reports the findings instead of throwing on
     * them, which is what makes them assertable in {@code @AfterAll}.
     *
     * <p>{@code recordFieldWrite} is how the detector learns about the write. Without the agent
     * ({@code -javaagent:async-test-agent.jar}) a bare {@code available--} is invisible to it.
     */
    @AsyncTest(threads = 4, invocations = 25, failOn = FailOn.NONE)
    void reserving_stock_from_four_threads_reports_a_race() {
        AsyncTestContext ctx = AsyncTestContext.get();
        if (ctx != null) {
            ctx.sharedRaceConditionDetector().recordFieldWrite(service, "available");
        }
        service.reserveOne();
    }

    /**
     * A wait that names itself. When it fails, the message says which wait timed out, how many
     * times the condition was evaluated, and what the condition last threw.
     */
    @Test
    void awaitUntil_names_the_wait_it_is_waiting_on() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            service.warmUpAsync(pool);

            AsyncAssert.awaitUntil(service::isWarmedUp, Duration.ofSeconds(2), "inventory warmed up");

            assertTrue(service.isWarmedUp());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedWait_says_what_the_condition_last_threw() {
        AssertionError error = assertThrows(AssertionError.class, () ->
                AsyncAssert.awaitUntil(
                        () -> { throw new IllegalStateException("warehouse offline"); },
                        Duration.ofMillis(50),
                        "inventory warmed up"));

        assertTrue(error.getMessage().contains("inventory warmed up"), error.getMessage());
        assertTrue(error.getMessage().contains("warehouse offline"), error.getMessage());
    }

    /**
     * {@code getResult()} returns null for "still running", "failed" and "completed with null"
     * alike. {@code requireResult()} separates the three.
     */
    @Test
    void futureCapture_tells_the_three_null_cases_apart() {
        AsyncAssert.FutureCapture<String> reserved = AsyncAssert.capture(service.reserveAsync("sku-1"));
        reserved.awaitDone(Duration.ofSeconds(2));

        assertTrue(reserved.isSuccess());
        assertEquals("reserved:sku-1", reserved.requireResult());

        AsyncAssert.FutureCapture<String> stillRunning =
                AsyncAssert.capture(new CompletableFuture<String>());
        assertFalse(stillRunning.isSuccess());
        assertFalse(stillRunning.isFailed());
        assertThrows(AssertionError.class, stillRunning::requireResult);
    }
}
