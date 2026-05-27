package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.FileProcessorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for FileProcessorService.
 *
 * ========================================================================
 * DETECTOR: ResourceLeakDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * FileProcessorService.processFile() opens a ByteArrayInputStream on every
 * call and stores it in a list, but never calls close(). Each invocation leaks
 * one stream. Under concurrent load (8 threads × 50 invocations = 400 calls)
 * 400 streams accumulate without being closed.
 *
 * WHY @Test PASSES:
 * A single call opens one stream. GC may reclaim it before the test framework
 * checks resource counts, so the test appears to pass without complaint.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ResourceLeakDetector tracks every registerResource / recordResourceOpened
 * event and waits for a matching recordResourceClosed. After all invocations
 * complete it reports every stream that was opened but never closed.
 *
 * DETECTORS TRIGGERED:
 *   ResourceLeakDetector — primary: detects unclosed InputStream instances
 *
 * FIX: wrap the stream creation in a try-with-resources block.
 */
class FileProcessorServiceTest {

    private FileProcessorService service;

    @BeforeEach
    void setUp() {
        service = new FileProcessorService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testProcessFile_singleThread_returnsNonZero() {
        int result = service.processFile("test-file.txt");
        assertTrue(result != 0, "Processed byte sum must be non-zero for non-empty input");
    }

    @Test
    void testProcessFile_differentPaths_returnsDistinctValues() {
        int r1 = service.processFile("alpha.txt");
        int r2 = service.processFile("beta.txt");
        assertNotEquals(r1, r2, "Different paths should produce different byte sums");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes stream leak
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see stream leak detected by ResourceLeakDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectResourceLeaks = true)
    void testProcessFile_concurrent_detectsStreamLeak() {
        // Call the service — internally creates a stream that is never closed
        service.processFile("concurrent-file.txt");

        InputStream leaked = service.getLastStream();
        if (leaked != null) {
            String name = "input-stream-" + Thread.currentThread().getName();

            // Register the resource with the detector
            AsyncTestContext.resourceLeakMonitor()
                    .registerResource(leaked, name, "InputStream");

            // Record that it was opened
            AsyncTestContext.resourceLeakMonitor()
                    .recordResourceOpened(leaked, name);

            // BUG: recordResourceClosed() is never called — the stream leaks
        }
    }
}
