package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PipelineService.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureChainDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * PipelineService.processAsync() builds a CompletableFuture chain containing a
 * step that throws on every 3rd call. The caller never joins the returned
 * future — the exception is silently swallowed, giving the impression of
 * success while the pipeline has failed.
 *
 * WHY @Test PASSES:
 * The first and second sequential invocations succeed. The third throws, but
 * because the future is fire-and-forget the exception never propagates to the
 * test body, so assertions never fail.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads concurrently call processAsync() and discard the future.
 * CompletableFutureChainDetector tracks created futures and checks whether
 * they are ever joined. It also detects chains that completed exceptionally
 * without an exception handler — flagging the silent failures.
 *
 * DETECTORS TRIGGERED:
 *   CompletableFutureChainDetector — primary: unawaited / unhandled CF chains
 *
 * FIX: join() the returned future, or attach .exceptionally(ex -> ...).
 */
class PipelineServiceTest {

    private PipelineService service;

    @BeforeEach
    void setUp() {
        service = new PipelineService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes even though bug exists
    // -----------------------------------------------------------------------

    @Test
    void testProcessAsync_firstCall_succeeds() throws Exception {
        CompletableFuture<String> future = service.processAsync("hello");
        // First call never hits the error branch; join() succeeds
        String result = future.join();
        assertTrue(result.contains("HELLO"));
    }

    @Test
    void testProcessAsync_fireAndForget_noVisibleError() {
        // Caller discards the future — exception on the 3rd call is invisible
        service.processAsync("a");
        service.processAsync("b");
        service.processAsync("c"); // throws internally, but nobody sees it
        // Test passes — bug is silently present
        assertTrue(true, "No exception propagates because future is discarded");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unawaited chains
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unawaited CF chains detected by CompletableFutureChainDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectCompletableFutureChainIssues = true, failOn = FailOn.LOW)
    void testPipeline_concurrent_detectsUnawaitedChain() {
        String input = "item-" + Thread.currentThread().getId();

        // Create the chain and register it with the detector
        CompletableFuture<String> future = service.processAsync(input);
        AsyncTestContext.get().cfChainDetector()
                .recordFutureCreated(future, "pipeline-chain");

        // BUG: we do NOT join the future — fire-and-forget pattern
        // The detector will flag this future as unawaited at analysis time.

        // Simulate the caller moving on without waiting for the result
        assertNotNull(future, "Future reference must not be null");
    }
}
