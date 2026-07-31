package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector;
import se.deversity.asynctest.example.service.AuditLogWriter;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for AuditLogWriter.
 *
 * ========================================================================
 * DETECTOR: FileChannelPositionRaceDetector
 *           (DetectorType.FILE_CHANNEL_POSITION_RACE)
 * ========================================================================
 *
 * FileChannel javadoc: "Where the position is affected, [operations] are
 * not safe for use by multiple concurrent threads." The channel object
 * survives concurrent use; the file's contents do not.
 *
 * THE BUG:
 *   - write(ByteBuffer) / read(ByteBuffer) use the channel's implicit
 *     position and advance it — one cursor shared by every thread
 *
 * THE FIX:
 *   - write(ByteBuffer, long) / read(ByteBuffer, long): the offset is
 *     explicit, the shared cursor is neither read nor moved. Reserve the
 *     offset with an AtomicLong, or use AsynchronousFileChannel.
 *
 * The detector distinguishes the two: recordPositionalAccess registers the
 * channel but never reports it, so a service that only ever uses the
 * positional overloads stays clean no matter how many threads touch it.
 */
class AuditLogWriterTest {

    @TempDir
    Path tempDir;

    private FileChannelPositionRaceDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FileChannelPositionRaceDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: positional writes from many threads. Safe, and reported as safe.
    // -----------------------------------------------------------------------

    @Test
    void positionalWritesAcrossThreads_areClean() throws Exception {
        Path file = tempDir.resolve("audit-positional.log");
        Object channel = new Object();          // stands in for the FileChannel instance

        try (var writer = new AuditLogWriter(file)) {
            Runnable worker = () -> detector.recordPositionalAccess(channel, "write(buf, pos)");
            Thread a = new Thread(worker, "auditor-a");
            Thread b = new Thread(worker, "auditor-b");
            a.start();
            b.start();
            a.join();
            b.join();

            writer.appendSafely("first");
            writer.appendSafely("second");

            // Explicit offsets: both records are intact and neither overwrote the other.
            assertEquals("first\n", writer.readAt(0, 6));
            assertEquals("second\n", writer.readAt(6, 7));
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Positional overloads never touch the shared cursor:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: implicit-position writes from two threads — flagged.
    // -----------------------------------------------------------------------

    @Test
    void implicitPositionWritesAcrossThreads_areDetected() throws Exception {
        Path file = tempDir.resolve("audit-implicit.log");
        Object channel = new Object();

        try (var writer = new AuditLogWriter(file)) {
            Runnable worker = () -> {
                detector.recordImplicitPositionAccess(channel, "write(buf)");
                try {
                    writer.append("record-" + Thread.currentThread().getName());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            Thread a = new Thread(worker, "auditor-a");
            Thread b = new Thread(worker, "auditor-b");
            a.start();
            b.start();
            a.join();
            b.join();
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected position-race violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("write(buf)"), violation);
        assertTrue(violation.contains("auditor-a"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: the damage itself — implicit appends whose positions collide
    // overwrite each other; positional appends do not.
    // -----------------------------------------------------------------------

    @Test
    void collidingImplicitAppends_overwriteEachOther() throws Exception {
        Path implicitLog = tempDir.resolve("collide-implicit.log");
        Path positionalLog = tempDir.resolve("collide-positional.log");

        // Two writers over the same file, each with its own channel and so its own cursor
        // starting at 0 — the same collision two threads sharing one cursor can produce.
        try (var first = new AuditLogWriter(implicitLog);
             var second = new AuditLogWriter(implicitLog)) {
            first.append("aaaaaaaa");
            second.append("bb");                       // lands at offset 0, over the first
        }

        try (var writer = new AuditLogWriter(positionalLog)) {
            writer.appendSafely("aaaaaaaa");
            writer.appendSafely("bb");
            assertEquals("aaaaaaaa\n", writer.readAt(0, 9), "explicit offsets do not collide");
            assertEquals("bb\n", writer.readAt(9, 3));
        }

        try (var reader = new AuditLogWriter(implicitLog)) {
            assertEquals("bb\n", reader.readAt(0, 3),
                    "the second record landed on top of the first");
            assertEquals(9L, reader.size(),
                    "the file is still the length of the first record — bytes were lost, "
                            + "not appended");
        }
    }
}
