package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.diagnostics.FileChannelPositionRaceDetector;
import se.deversity.asynctest.example.service.AuditLogWriter;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * FileChannel javadoc: "File channels are safe for use by multiple concurrent
 * threads. [...] Only one operation that involves the channel's position or
 * can change its file's size may be in progress at any given time". One
 * read(ByteBuffer) or write(ByteBuffer) is therefore whole; two calls are not
 * one operation.
 *
 * THE BUG:
 *   - position(n) and then read(ByteBuffer) or write(ByteBuffer) relying on
 *     it: another thread's call can land between the two and move the one
 *     cursor every thread shares
 *
 * THE FIX:
 *   - read(ByteBuffer, long) / write(ByteBuffer, long): the offset is
 *     explicit, the shared cursor is neither read nor moved. Or hold one lock
 *     across the seek and the I/O on every thread, or use
 *     AsynchronousFileChannel.
 *
 * The detector reports the seek-then-I/O sequence, not sharing as such:
 * recordPositionalAccess registers the channel but never reports it, and
 * self-contained implicit calls with no seek before them are no finding.
 */
class AuditLogWriterTest {

    private static final int THREADS = 4;
    private static final int RECORDS_PER_THREAD = 50;

    @TempDir
    Path tempDir;

    private FileChannelPositionRaceDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FileChannelPositionRaceDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: positional reads from many threads. Safe, and reported as safe.
    // -----------------------------------------------------------------------

    @Test
    void positionalReadsAcrossThreads_areClean() throws Exception {
        Object channel = new Object();          // stands in for the FileChannel instance

        try (var log = new AuditLogWriter(tempDir.resolve("audit-positional.log"))) {
            log.append("first");
            log.append("second");
            runOnThreads(2, worker -> {
                detector.recordPositionalAccess(channel, "read(buf, pos)");
                assertEquals("second", log.readRecordAt(1));
            });
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Positional overloads never touch the shared cursor:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: self-contained appends from many threads. Every record whole,
    // none lost, and no finding: nothing relied on where the cursor was.
    // -----------------------------------------------------------------------

    @Test
    void selfContainedAppendsAcrossThreads_landWholeAndAreClean() throws Exception {
        Object channel = new Object();

        try (var log = new AuditLogWriter(tempDir.resolve("audit-appends.log"))) {
            runOnThreads(THREADS, worker -> {
                for (int i = 0; i < RECORDS_PER_THREAD; i++) {
                    detector.recordImplicitPositionAccess(channel, "write(buf)");
                    log.append("w" + worker + "-" + i);
                }
            });

            int total = THREADS * RECORDS_PER_THREAD;
            assertEquals((long) total * AuditLogWriter.RECORD_SIZE, log.size(), "no record lost");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < total; i++) {
                seen.add(log.readRecordAt(i));
            }
            assertEquals(total, seen.size(), "every record whole and distinct: " + seen);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Self-contained implicit writes lose nothing:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: seek-then-read from two threads, unguarded. Flagged.
    // -----------------------------------------------------------------------

    @Test
    void seekThenReadAcrossThreads_isDetected() throws Exception {
        Object channel = new Object();

        try (var log = new AuditLogWriter(tempDir.resolve("audit-seek.log"))) {
            log.append("first");
            log.append("second");
            runOnThreads(2, worker -> {
                detector.recordImplicitPositionAccess(channel, "position(n)");
                detector.recordImplicitPositionAccess(channel, "read(buf)");
                log.readRecord(worker);
            });
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected position-race violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("position(n)"), violation);
        assertTrue(violation.contains("auditor-0"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 4: the damage itself, replayed in one order on one channel: another
    // thread's readRecord lands between this thread's seek and its read, and
    // the read returns the wrong record. The positional read does not care.
    // -----------------------------------------------------------------------

    @Test
    void aCallBetweenSeekAndRead_returnsTheWrongRecord() throws Exception {
        Path file = tempDir.resolve("audit-interleaved.log");
        FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        try (var log = new AuditLogWriter(channel)) {
            for (String record : List.of("record-0", "record-1", "record-2", "record-3", "record-4")) {
                log.append(record);
            }

            // This thread wants record 1: it seeks...
            channel.position(AuditLogWriter.RECORD_SIZE);
            // ...another thread's whole readRecord(3) lands here and leaves the cursor after record 3...
            assertEquals("record-3", log.readRecord(3));
            // ...and this thread's read, relying on its seek, gets record 4.
            ByteBuffer buffer = ByteBuffer.allocate(AuditLogWriter.RECORD_SIZE);
            channel.read(buffer);
            String got = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).trim();
            assertEquals("record-4", got, "the seek to record 1 was undone by the call in between");

            assertEquals("record-1", log.readRecordAt(1), "the positional read has no cursor to lose");
        }
    }

    /** One worker body; {@code worker} is its index. */
    @FunctionalInterface
    private interface Worker {
        void run(int worker) throws Exception;
    }

    private static void runOnThreads(int count, Worker body) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < count; i++) {
            int worker = i;
            threads.add(new Thread(() -> {
                try {
                    body.run(worker);
                } catch (Throwable t) {
                    failures.add(t);
                }
            }, "auditor-" + i));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("a worker failed", failures.get(0));
        }
    }
}
