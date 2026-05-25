package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DataPipelineService.
 *
 * ========================================================================
 * DETECTOR: StreamClosingDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (GC may eventually close streams; no OS pressure)
 * - The same scenario with @AsyncTest FAILS (unclosed streams accumulate fast)
 *
 * THE BUG:
 * DataPipelineService.openPipeline() opens a stream per call without closing
 * it. With 8 threads × 50 invocations = 400 streams opened and none closed,
 * the OS file-descriptor table fills up and subsequent opens fail.
 *
 * WHY @Test PASSES:
 * A handful of sequential calls rarely exhaust the descriptor limit, and the
 * GC may finalize unclosed streams before the limit is reached in small tests.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StreamClosingDetector tracks every recordStreamOpened() / recordStreamClosed()
 * pair. Streams that appear in opened-but-not-closed are flagged in the report.
 *
 * DETECTORS TRIGGERED:
 *   StreamClosingDetector — primary: finds streams opened without a matching close
 *
 * FIX: wrap I/O-backed streams in try-with-resources to guarantee closure.
 */
class DataPipelineServiceTest {

    private DataPipelineService service;

    @BeforeEach
    void setUp() {
        service = new DataPipelineService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_openPipeline_returnsFilteredLines() {
        var result = service.openPipeline(List.of("hello", "", "world"));
        assertEquals(2, result.size());
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("world"));
    }

    @Test
    void test_singleThread_blankLinesExcluded() {
        var result = service.openPipeline(List.of("  ", "\t", "data"));
        assertEquals(1, result.size());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unclosed stream resource leak
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unclosed streams detected by StreamClosingDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectStreamClosing = true)
    void test_concurrent_detectsUnclosedStream() {
        var detector = AsyncTestContext.get().streamClosingDetector();

        // Open a simulated I/O-backed stream via the service.
        service.openPipeline(List.of("line1", "line2", "line3"));

        // Register this stream with the detector so it tracks the lifecycle.
        // The service never calls close(), so recordStreamClosed is never called.
        Stream<String> opened = Stream.of("line1", "line2", "line3");
        String streamName = "data-pipeline-" + Thread.currentThread().getName();
        detector.recordStreamOpened(opened, streamName);
        // BUG: recordStreamClosed() is intentionally not called here.
        // In production the stream would be Files.lines(path) with no close().
    }
}
