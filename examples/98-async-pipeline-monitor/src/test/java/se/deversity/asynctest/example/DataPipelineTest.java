package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DataPipeline.
 *
 * ========================================================================
 * DETECTOR: PipelineMonitor
 * ========================================================================
 *
 * THE BUG:
 * DataPipeline runs parse → enrich → persist stages with no back-pressure.
 * Enrich is sometimes slow (10 ms) and persist is always slow (20 ms). Under
 * concurrent load, fast producers overwhelm slow consumers. When threads are
 * interrupted, persist fails and events are lost — published count exceeds
 * processed count.
 *
 * WHY @Test PASSES:
 * Single-threaded tests process one message at a time; each stage completes
 * before the next starts. No interruption occurs and persist always succeeds.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads, PipelineMonitor tracks published vs processed vs failed events
 * per stage. The slow persist stage fails some messages under concurrency,
 * creating an unaccounted event gap that the monitor flags as signal loss.
 *
 * FIX:
 * Add bounded BlockingQueue hand-offs between stages for back-pressure.
 */
class DataPipelineTest {

    private DataPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new DataPipeline();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testProcessMessage_singleMessage_noException() {
        assertDoesNotThrow(() -> pipeline.processMessage("test-event-1"));
    }

    @Test
    void testProcessMessage_afterCall_persistCountIncremented() throws Exception {
        pipeline.processMessage("event-a");
        assertEquals(1, pipeline.getPersistCount());
    }

    @Test
    void testGetParseCount_afterOneMessage_isOne() throws Exception {
        pipeline.processMessage("event-b");
        assertEquals(1, pipeline.getParseCount());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the pipeline imbalance
    // -------------------------------------------------------------------------

    /**
     * Eight threads each publish a message and attempt to process it through
     * all three stages. PipelineMonitor records published, processed, and failed
     * events per stage and reports stages where the numbers don't add up.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: add bounded BlockingQueue between stages for back-pressure
     */
    @Disabled("Remove @Disabled to see the bug detected by PipelineMonitor")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, monitorAsyncPipeline = true)
    void test_concurrent_detectsPipelineImbalance() {
        PipelineMonitor mon = AsyncTestContext.pipelineMonitor();
        mon.registerStage("parse");
        mon.registerStage("enrich");
        mon.registerStage("persist");

        String eventId = "evt-" + Thread.currentThread().getName();

        mon.recordEventPublished("parse", eventId);
        mon.recordEventPublished("enrich", eventId);
        mon.recordEventPublished("persist", eventId);

        try {
            pipeline.processMessage(eventId);
            mon.recordEventProcessed("parse", eventId);
            mon.recordEventProcessed("enrich", eventId);
            mon.recordEventProcessed("persist", eventId);
        } catch (RuntimeException e) {
            // Persist interrupted under load — event is lost
            mon.recordEventFailed("persist", eventId, e.getMessage());
        }
    }

    // Inner alias to avoid fully-qualified name in test
    private static class PipelineMonitor extends se.deversity.asynctest.diagnostics.PipelineMonitor {}
}
