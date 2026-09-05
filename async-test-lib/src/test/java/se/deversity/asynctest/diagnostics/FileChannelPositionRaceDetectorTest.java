package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileChannelPositionRaceDetector}.
 *
 * <p>The detector tracks channels by identity only ({@code Object} channel);
 * plain {@code Object} stand-ins are used here instead of a real
 * {@code FileChannel} — no file I/O is needed to exercise the bookkeeping and
 * violation logic.
 */
class FileChannelPositionRaceDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new FileChannelPositionRaceDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadImplicitAccessIsNotFlagged() {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        for (int i = 0; i < 5; i++) {
            d.recordImplicitPositionAccess(channel, "read");
            d.recordImplicitPositionAccess(channel, "write");
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedImplicitPositionAccessAcrossThreadsIsFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "read");
        Thread t = new Thread(() -> d.recordImplicitPositionAccess(channel, "write"));
        t.start();
        t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("2 threads"), "Message should count threads: " + msg);
        assertTrue(msg.contains("read"), "Message should mention observed operation: " + msg);
        assertTrue(msg.contains("write"), "Message should mention observed operation: " + msg);

        assertEquals(1, report.structuredViolations.size());
        var v = report.structuredViolations.get(0);
        assertEquals("FileChannelPositionRace", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(2, v.attributes().get("threadCount"));
    }

    @Test
    void positionalOnlyAccessAcrossThreadsIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordPositionalAccess(channel, "read");
        Thread t = new Thread(() -> d.recordPositionalAccess(channel, "write"));
        t.start();
        t.join();

        assertFalse(d.analyze().hasIssues(),
                "Positional read(buf, pos)/write(buf, pos) never touch the shared cursor and must not be flagged");
    }

    @Test
    void positionalAccessDoesNotContributeToImplicitViolationThreadCount() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "read");
        Thread t = new Thread(() -> d.recordPositionalAccess(channel, "write"));
        t.start();
        t.join();

        assertFalse(d.analyze().hasIssues(),
                "A single implicit-position thread plus a positional-only thread is not a race");
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object a = new Object();
        Object b = new Object();
        d.recordImplicitPositionAccess(a, "read");
        d.recordImplicitPositionAccess(b, "read");
        Thread t = new Thread(() -> d.recordImplicitPositionAccess(a, "read"));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void nullsAreIgnored() {
        var d = new FileChannelPositionRaceDetector();
        d.recordImplicitPositionAccess(null, "read");
        d.recordPositionalAccess(null, "read");
        d.recordImplicitPositionAccess(new Object(), null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportDescribesHazardAndFix() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        Thread t = new Thread(() -> d.recordImplicitPositionAccess(channel, "transferFrom"));
        t.start();
        t.join();

        String reportText = d.analyze().toString();
        assertTrue(reportText.contains("interleaves I/O"), "Should describe the hazard: " + reportText);
        assertTrue(reportText.contains("read(buffer, position) / write(buffer, position)"),
                "Fix hint should mention the positional overloads: " + reportText);
        assertTrue(reportText.contains("AsynchronousFileChannel"),
                "Fix hint should mention AsynchronousFileChannel: " + reportText);
        assertTrue(reportText.contains("one FileChannel per thread"),
                "Fix hint should mention per-thread channels: " + reportText);
    }

    @Test
    void analyzeIsIdempotent() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "read");
        Thread t = new Thread(() -> d.recordImplicitPositionAccess(channel, "write"));
        t.start();
        t.join();

        var first = d.analyze();
        var second = d.analyze();
        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void implicitPositionAccessUnderTheChannelsMonitorIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        synchronized (channel) {
            d.recordImplicitPositionAccess(channel, "read");
        }
        Thread t = new Thread(() -> {
            synchronized (channel) {
                d.recordImplicitPositionAccess(channel, "write");
            }
        });
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues(),
            "both threads held the channel's monitor around the cursor-moving call, so the "
                + "cursor cannot interleave: " + d.analyze());
    }
}
