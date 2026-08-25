package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.PipelineMonitor;
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
 * DataPipeline hands messages to the persist stage through a bounded queue,
 * written with offer(), whose false return nobody acts on. Parse and enrich are
 * fast, persist is slow, and once the queue between them fills, every further
 * message is dropped. Not failed - dropped. Nothing throws, nothing is logged,
 * and the parse and enrich counters upstream still say it was handled.
 *
 * WHY @Test PASSES:
 * One message at a time never fills a queue of four.
 * testProcessMessage_whenTheHandoffIsFull_dropsSilently shows what happens when
 * it does, with no detector involved.
 *
 * WHY @AsyncTest DETECTS:
 * PipelineMonitor compares published against processed and failed per stage, and
 * reports what is left over. A dropped message is published and then neither, so
 * it lands in exactly that gap. The old demonstration recorded a published and a
 * processed event for all three stages and only recorded a failure when
 * processMessage threw - which it did only on InterruptedException, which never
 * happened. published equalled processed everywhere, nothing was unaccounted for,
 * and the report was empty. See issue #346.
 *
 * FIX:
 * put() instead of offer(), so a full queue slows the producer down. Or act on
 * the false: retry, spill to disk, or at minimum count it somewhere a human will
 * look.
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
    void testProcessMessage_singleMessage_isAccepted() {
        assertTrue(pipeline.processMessage("test-event-1"),
                "an empty hand-off queue accepts the message");
    }

    @Test
    void testProcessMessage_afterDrain_persistCountIncremented() {
        pipeline.processMessage("event-a");

        assertEquals(1, pipeline.drainPersist());
        assertEquals(1, pipeline.getPersistCount());
    }

    @Test
    void testGetParseCount_afterOneMessage_isOne() {
        pipeline.processMessage("event-b");
        assertEquals(1, pipeline.getParseCount());
    }

    /**
     * The bug itself, and it needs no detector: once the hand-off queue is full, messages are
     * dropped, and the only sign of it is a return value nobody checks.
     */
    @Test
    void testProcessMessage_whenTheHandoffIsFull_dropsSilently() {
        int overflow = 3;
        for (int i = 0; i < DataPipeline.PERSIST_QUEUE_CAPACITY; i++) {
            assertTrue(pipeline.processMessage("event-" + i), "queue still has room");
        }

        for (int i = 0; i < overflow; i++) {
            assertFalse(pipeline.processMessage("overflow-" + i),
                    "the queue is full, so this message is gone");
        }

        assertEquals(overflow, pipeline.getDroppedCount());
        assertEquals(DataPipeline.PERSIST_QUEUE_CAPACITY + overflow, pipeline.getParseCount(),
                "every message was parsed and enriched, which is what makes the loss invisible "
                        + "from upstream");
    }

    /**
     * The monitor's positive direction: a message published into a stage that neither processed
     * nor failed it is unaccounted for, which is exactly what a dropped message is.
     */
    @Test
    void testPipelineMonitor_droppedMessage_reports() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("persist");

        for (int i = 0; i < DataPipeline.PERSIST_QUEUE_CAPACITY + 1; i++) {
            monitor.recordEventPublished("persist", "event-" + i);
            if (pipeline.processMessage("event-" + i)) {
                monitor.recordEventProcessed("persist", "event-" + i);
            }
        }

        assertTrue(monitor.analyzePipeline().hasIssues(),
                "one message published and never accounted for is signal loss");
    }

    /**
     * And the other direction: a pipeline whose every published message is accounted for has
     * nothing to report, however slow it is.
     */
    @Test
    void testPipelineMonitor_everyMessageAccountedFor_isSilent() {
        PipelineMonitor monitor = new PipelineMonitor();
        monitor.registerStage("persist");

        for (int i = 0; i < DataPipeline.PERSIST_QUEUE_CAPACITY; i++) {
            monitor.recordEventPublished("persist", "event-" + i);
            assertTrue(pipeline.processMessage("event-" + i));
            monitor.recordEventProcessed("persist", "event-" + i);
        }

        assertFalse(monitor.analyzePipeline().hasIssues(),
                "published equals processed, so nothing went missing");
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the pipeline imbalance
    // -------------------------------------------------------------------------

    /**
     * Eight threads each publish a message into a hand-off queue that holds four. The first
     * four are accepted; everything after that is dropped, and nothing upstream notices.
     * PipelineMonitor reports the gap between what was published and what was accounted for.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      persist: N published, 4 processed, 0 failed, N-4 unaccounted
     * 3. To fix: put() instead of offer(), so a full queue slows the producer down; or act on
     *    the false return rather than discarding it
     */
    @Disabled("Remove @Disabled to see the bug detected by PipelineMonitor")
    @AsyncTest(threads = 8, invocations = 5, detectAll = false,
            monitorAsyncPipeline = true, failOn = FailOn.LOW)
    void test_concurrent_detectsPipelineImbalance() {
        // The old demonstration recorded a published and a processed event for all three
        // stages, and only recorded a failure when processMessage threw - which it did only on
        // InterruptedException, which never happened. published == processed for every stage,
        // nothing unaccounted, empty report. See issue #346.
        PipelineMonitor monitor = AsyncTestContext.pipelineMonitor();
        monitor.registerStage("persist");

        String eventId = "evt-" + Thread.currentThread().threadId();
        monitor.recordEventPublished("persist", eventId);

        if (pipeline.processMessage(eventId)) {
            monitor.recordEventProcessed("persist", eventId);
        }
        // BUG: and if it was not accepted, nothing is recorded at all - because nothing in the
        // pipeline knows the message is gone. That silence is the finding.
    }
}
