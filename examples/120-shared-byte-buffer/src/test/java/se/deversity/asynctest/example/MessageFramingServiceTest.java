package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedByteBufferDetector;
import se.deversity.asynctest.example.service.MessageFramingService;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for MessageFramingService.
 *
 * ========================================================================
 * DETECTOR: SharedByteBufferDetector  (DetectorType.SHARED_BYTE_BUFFER)
 * ========================================================================
 *
 * java.nio.Buffer javadoc: "Buffers are not safe for use by multiple
 * concurrent threads. If a buffer is to be used by more than one thread
 * then access to the buffer should be controlled by appropriate
 * synchronization."
 *
 * THE BUG:
 *   - one ByteBuffer field framing every thread's message; clear(), the
 *     relative put()s, flip() and the relative get() all move one cursor
 *
 * THE FIX:
 *   - a buffer per call (or per thread via ThreadLocal), or synchronize the
 *     whole clear-through-get sequence — not the individual calls
 *
 * Note which accesses count. The detector separates *position-mutating*
 * operations from *absolute* ones: get(int)/put(int, ..) leave the cursor
 * alone and are recorded as context only. Two threads doing absolute reads
 * on one buffer is fine, and the detector says so.
 */
class MessageFramingServiceTest {

    private SharedByteBufferDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedByteBufferDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: a buffer per call. No sharing, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void bufferPerCall_isClean() {
        var service = new MessageFramingService();

        for (int i = 0; i < 4; i++) {
            ByteBuffer perCall = ByteBuffer.allocate(64);       // models frameSafely's buffer
            detector.recordPositionalAccess(perCall, "putInt");
            detector.recordPositionalAccess(perCall, "flip");
            byte[] frame = service.frameSafely("message-" + i);
            assertEquals(Integer.BYTES + ("message-" + i).length(), frame.length);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: one buffer, two threads mutating its position — flagged.
    // -----------------------------------------------------------------------

    @Test
    void sharedBufferAcrossThreads_isDetected() throws Exception {
        var service = new MessageFramingService();
        ByteBuffer shared = ByteBuffer.allocate(1024);   // models the sharedScratch field

        Runnable worker = () -> {
            detector.recordPositionalAccess(shared, "clear");
            detector.recordPositionalAccess(shared, "putInt");
            detector.recordPositionalAccess(shared, "flip");
            service.frame("payload-" + Thread.currentThread().getName());
        };
        Thread a = new Thread(worker, "writer-a");
        Thread b = new Thread(worker, "writer-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-buffer violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("writer-a"), violation);
        assertTrue(violation.contains("writer-b"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: absolute access from many threads is NOT a violation.
    // -----------------------------------------------------------------------

    @Test
    void absoluteAccessAcrossThreads_isNotFlagged() throws Exception {
        ByteBuffer shared = ByteBuffer.allocate(64);

        Runnable reader = () -> detector.recordAbsoluteAccess(shared, "get(int)");
        Thread a = new Thread(reader, "reader-a");
        Thread b = new Thread(reader, "reader-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "get(int)/put(int,..) do not touch position/limit/mark:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: the corruption itself — interleaved framing on one buffer
    // produces a frame whose header does not match its body.
    // -----------------------------------------------------------------------

    @Test
    void interleavedFraming_producesMismatchedHeader() {
        ByteBuffer shared = ByteBuffer.allocate(1024);

        // Thread 1 gets as far as writing its length header...
        shared.clear();
        shared.putInt("first-message".length());
        // ...thread 2's clear() lands here and resets the cursor...
        shared.clear();
        shared.putInt("second".length());
        shared.put("second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // ...and thread 1 resumes, appending its body after thread 2's frame.
        shared.put("first-message".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        shared.flip();

        int declaredLength = shared.getInt();
        int actualBodyLength = shared.remaining();

        assertEquals("second".length(), declaredLength,
                "the surviving header is thread 2's");
        assertTrue(actualBodyLength > declaredLength,
                "but the body carries both messages: header " + declaredLength
                        + " bytes vs body " + actualBodyLength + " bytes");
    }
}
