package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedCharsetCoderDetector;
import se.deversity.asynctest.example.service.TextEncodingService;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for TextEncodingService.
 *
 * ========================================================================
 * DETECTOR: SharedCharsetCoderDetector  (DetectorType.SHARED_CHARSET_CODER)
 * ========================================================================
 *
 * Charset is immutable and thread-safe. CharsetEncoder and CharsetDecoder
 * are state machines: they track surrogate-pair carry-over, end-of-input,
 * and the flush stage. reset() exists because that state survives calls.
 *
 * THE BUG:
 *   - one CharsetEncoder/CharsetDecoder cached in a field, used by every
 *     thread; the documented reset -> encode -> flush protocol is per coder,
 *     not per thread
 *
 * THE FIX:
 *   - Charset.newEncoder() per call (it is a small allocation), a
 *     ThreadLocal<CharsetEncoder> if profiling really says otherwise, or
 *     String.getBytes(charset) / new String(bytes, charset) when the coder's
 *     error actions are not needed — those allocate their own coder.
 */
class TextEncodingServiceTest {

    private SharedCharsetCoderDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedCharsetCoderDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: a coder per call, single thread. Nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void encoderPerCall_isClean() throws Exception {
        var service = new TextEncodingService();

        for (int i = 0; i < 3; i++) {
            CharsetEncoder perCall = StandardCharsets.UTF_8.newEncoder();
            detector.recordAccess(perCall, "encode", Thread.currentThread());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                    service.encodeSafely("hello"));
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: one encoder, two threads — flagged.
    // -----------------------------------------------------------------------

    @Test
    void sharedEncoderAcrossThreads_isDetected() throws Exception {
        var service = new TextEncodingService();
        CharsetEncoder shared = StandardCharsets.UTF_8.newEncoder();  // models the field

        Runnable worker = () -> {
            detector.recordAccess(shared, "reset", Thread.currentThread());
            detector.recordAccess(shared, "encode", Thread.currentThread());
        };
        Thread a = new Thread(worker, "encoder-a");
        Thread b = new Thread(worker, "encoder-b");
        a.start();
        b.start();
        a.join();
        b.join();

        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), service.encode("payload"));

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-encoder violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("CharsetEncoder"), violation);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("encoder-a"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: decoders are tracked too, and separately from encoders.
    // -----------------------------------------------------------------------

    @Test
    void sharedDecoderAcrossThreads_isDetectedSeparately() throws Exception {
        CharsetDecoder shared = StandardCharsets.UTF_8.newDecoder();

        Runnable worker = () -> detector.recordAccess(shared, "decode", Thread.currentThread());
        Thread a = new Thread(worker, "decoder-a");
        Thread b = new Thread(worker, "decoder-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-decoder violation:\n" + report);
        assertEquals(1, report.violations.size(), "one instance, one violation");
        assertTrue(report.violations.get(0).contains("CharsetDecoder"), report.violations.get(0));
    }

    // -----------------------------------------------------------------------
    // Part 4: the service still round-trips correctly single-threaded — which
    // is exactly why this bug survives code review and unit tests.
    // -----------------------------------------------------------------------

    @Test
    void sharedCoders_lookCorrectSingleThreaded() throws Exception {
        var service = new TextEncodingService();

        byte[] encoded = service.encode("round trip åäö");
        assertEquals("round trip åäö", service.decode(encoded));
    }
}
