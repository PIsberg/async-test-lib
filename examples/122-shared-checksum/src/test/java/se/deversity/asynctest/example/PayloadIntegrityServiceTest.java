package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedChecksumDetector;
import se.deversity.asynctest.example.service.PayloadIntegrityService;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for PayloadIntegrityService.
 *
 * ========================================================================
 * DETECTOR: SharedChecksumDetector  (DetectorType.SHARED_CHECKSUM)
 * ========================================================================
 *
 * java.util.zip.Checksum implementations (CRC32, CRC32C, Adler32) hold a
 * running value that update() folds bytes into. reset -> update -> getValue
 * is a read-modify-write, and none of the three calls is synchronized.
 *
 * THE BUG:
 *   - one CRC32 field checksumming every thread's payload
 *
 * THE FIX:
 *   - a Checksum per call; the object is a long and a lookup table
 *
 * What makes this one dangerous is that it never throws. The result is a
 * well-formed checksum over the concatenation of two payloads — it just
 * matches neither, and you learn that from a downstream integrity failure
 * on a file that was never damaged.
 */
class PayloadIntegrityServiceTest {

    private SharedChecksumDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedChecksumDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: a Checksum per call. Nothing shared, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void checksumPerCall_isClean() {
        var service = new PayloadIntegrityService();

        for (int i = 0; i < 3; i++) {
            CRC32 perCall = new CRC32();
            detector.recordAccess(perCall, "update", Thread.currentThread());
            detector.recordAccess(perCall, "getValue", Thread.currentThread());
            service.checksumSafely("payload-" + i);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: one Checksum, two threads — flagged.
    // -----------------------------------------------------------------------

    @Test
    void sharedChecksumAcrossThreads_isDetected() throws Exception {
        var service = new PayloadIntegrityService();
        CRC32 shared = new CRC32();          // models the sharedChecksum field

        Runnable worker = () -> {
            detector.recordAccess(shared, "reset", Thread.currentThread());
            detector.recordAccess(shared, "update", Thread.currentThread());
            detector.recordAccess(shared, "getValue", Thread.currentThread());
            service.checksum("payload-" + Thread.currentThread().getName());
        };
        Thread a = new Thread(worker, "hasher-a");
        Thread b = new Thread(worker, "hasher-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-checksum violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("CRC32"), violation);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("hasher-a"), violation);
        assertTrue(violation.contains("hasher-b"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: the wrong answer itself — an interleaved update produces a
    // checksum that is valid, stable, and matches neither payload.
    // -----------------------------------------------------------------------

    @Test
    void interleavedUpdates_produceAChecksumThatMatchesNeitherPayload() {
        var service = new PayloadIntegrityService();

        long expectedA = service.checksumSafely("payload-a");
        long expectedB = service.checksumSafely("payload-b");

        // The interleaving a race produces on the shared accumulator: thread A resets and
        // updates, thread B's update lands before A reads the value.
        CRC32 shared = new CRC32();
        shared.reset();
        shared.update("payload-a".getBytes(StandardCharsets.UTF_8));
        shared.update("payload-b".getBytes(StandardCharsets.UTF_8));   // intruder bytes
        long observed = shared.getValue();

        assertNotEquals(expectedA, observed, "must not match payload-a");
        assertNotEquals(expectedB, observed, "must not match payload-b");

        // ...and it is perfectly reproducible, which is why nothing downstream suspects a
        // race: it looks like the payload changed, not like the checksum broke.
        CRC32 again = new CRC32();
        again.update("payload-apayload-b".getBytes(StandardCharsets.UTF_8));
        assertEquals(again.getValue(), observed);
    }
}
