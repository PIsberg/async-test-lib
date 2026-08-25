package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.RequestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RequestLogger.
 *
 * ========================================================================
 * DETECTOR: StringBuilderDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (single thread, no interleaving)
 * - The same scenario with @AsyncTest FAILS (garbled output / AIOOBE)
 *
 * THE BUG:
 * RequestLogger.append() calls log.append(msg).append("\n") on a shared
 * StringBuilder instance field with no synchronization. StringBuilder's
 * internal char[] is not protected against concurrent access — two threads
 * resizing the buffer simultaneously corrupt the internal state.
 *
 * WHY @Test PASSES:
 * A single thread appending messages one at a time never interleaves with
 * itself. The output is always correct and no exceptions are thrown.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StringBuilderDetector.recordAppend() is called by each thread. When the
 * detector sees more than one thread calling append on the same builder
 * instance, it records a concurrent-access violation in the report.
 *
 * DETECTORS TRIGGERED:
 *   StringBuilderDetector — primary: detects concurrent appends on a shared builder
 *
 * FIX: synchronize on the builder, use StringBuffer, or accumulate per-thread
 *      and merge at the end.
 */
class RequestLoggerTest {

    private RequestLogger logger;

    @BeforeEach
    void setUp() {
        logger = new RequestLogger();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_appendsMessage() {
        logger.append("request-1");
        assertTrue(logger.getLog().contains("request-1"));
    }

    @Test
    void test_singleThread_multipleMessages_appear() {
        logger.append("msg-a");
        logger.append("msg-b");
        String log = logger.getLog();
        assertTrue(log.contains("msg-a"));
        assertTrue(log.contains("msg-b"));
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes concurrent StringBuilder access
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see concurrent StringBuilder access detected by StringBuilderDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectStringBuilderIssues = true, failOn = FailOn.LOW)
    void test_concurrent_detectsSharedBuilder() {
        var detector = AsyncTestContext.get().stringBuilderMonitor();
        var builder = logger.getRawBuilder();

        // Register the shared builder once; the detector deduplicates by identity.
        detector.registerBuilder(builder, "request-log");

        // Record the append from this thread — detector tracks which threads write.
        String msg = "request-from-" + Thread.currentThread().getName();
        detector.recordAppend(builder, "request-log");

        // Perform the actual (buggy) unsynchronized append.
        logger.append(msg);
    }
}
