package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ResponseCompressor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ResponseCompressor.
 *
 * ========================================================================
 * DETECTOR: SharedDeflaterDetector
 * ========================================================================
 *
 * THE BUG:
 * ResponseCompressor holds a single java.util.zip.Deflater in a field and shares it
 * across all threads. A Deflater wraps a stateful native zlib stream and is not
 * thread-safe: concurrent reset()/setInput()/deflate() calls interleave on the same
 * native state, corrupting the compressed output or producing garbage bytes.
 *
 * WHY @Test PASSES:
 * Single-threaded tests always run their full compress() sequence start to finish
 * before any other thread touches the deflater. A Deflater works fine in isolation.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads sharing the same ResponseCompressor instance, SharedDeflaterDetector
 * tracks which threads access the same Deflater and reports the multi-thread access
 * pattern.
 *
 * FIX:
 * Use one Deflater per thread (e.g. a ThreadLocal) and call end() in a finally block,
 * or create a fresh Deflater per call and end() it when done.
 */
class ResponseCompressorTest {

    private ResponseCompressor compressor;

    @BeforeEach
    void setUp() {
        compressor = new ResponseCompressor();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testCompress_singleThread_returnsNonEmpty() {
        byte[] compressed = compressor.compress("hello world".getBytes());
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
    }

    @Test
    void testCompress_compressibleInput_isNotLarger() {
        byte[] input = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes();
        byte[] compressed = compressor.compress(input);
        assertTrue(compressed.length <= input.length);
    }

    @Test
    void testCompress_emptyInput_returnsNonNull() {
        byte[] compressed = compressor.compress(new byte[0]);
        assertNotNull(compressed);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the shared Deflater bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads concurrently compress payloads using the same ResponseCompressor.
     * SharedDeflaterDetector records all accesses and reports that the same Deflater
     * instance is used from multiple threads unsafely.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: use one Deflater per thread (ThreadLocal) with end() in finally
     */
    @Disabled("Remove @Disabled to see the bug detected by SharedDeflaterDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSharedDeflater = true)
    void test_concurrent_detectsSharedDeflater() {
        Thread thread = Thread.currentThread();

        // Instrument: record that this thread accesses the shared Deflater
        AsyncTestContext.sharedDeflaterDetector()
                .recordAccess(compressor.getDeflater(), "response-gzip", thread);

        compressor.compress(("payload-" + thread.getName()).getBytes());
    }
}
