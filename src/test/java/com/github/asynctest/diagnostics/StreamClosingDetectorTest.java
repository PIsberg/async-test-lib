package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StreamClosingDetector}.
 */
class StreamClosingDetectorTest {

    private StreamClosingDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StreamClosingDetector();
    }

    @Test
    void testNoIssuesWhenStreamsProperlyClosed() {
        Closeable stream = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream, "test-stream");
        detector.recordStreamClosed(stream, "test-stream");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
        assertEquals(1, report.totalOpened);
        assertEquals(1, report.totalClosed);
    }

    @Test
    void testDetectsUnclosedStreams() {
        Closeable stream = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream, "unclosed-stream");
        // Missing: recordStreamClosed

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.unclosedStreams.isEmpty());
        assertTrue(report.unclosedStreams.get(0).contains("unclosed-stream"));
    }

    @Test
    void testDetectsMultipleUnclosedStreams() {
        Closeable stream1 = new ByteArrayInputStream(new byte[0]);
        Closeable stream2 = new ByteArrayOutputStream();

        detector.recordStreamOpened(stream1, "stream-1");
        detector.recordStreamOpened(stream2, "stream-2");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertEquals(2, report.unclosedStreams.size());
    }

    @Test
    void testDisabledDetectorReturnsNoIssues() {
        Closeable stream = new ByteArrayInputStream(new byte[0]);

        detector.disable();
        detector.recordStreamOpened(stream, "test-stream");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testTracksMaxConcurrentOpenStreams() {
        Closeable stream1 = new ByteArrayInputStream(new byte[0]);
        Closeable stream2 = new ByteArrayInputStream(new byte[0]);
        Closeable stream3 = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream1, "stream-1");
        detector.recordStreamOpened(stream2, "stream-2");
        detector.recordStreamOpened(stream3, "stream-3");
        
        // Close two before analyzing
        detector.recordStreamClosed(stream1, "stream-1");
        detector.recordStreamClosed(stream2, "stream-2");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertNotNull(report);
        assertEquals(3, report.maxConcurrentOpen);
        assertEquals(1, report.unclosedStreams.size()); // stream-3 still open
    }

    @Test
    void testReportToStringContainsIssues() {
        Closeable stream = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream, "leaking-stream");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("STREAM CLOSING ISSUES DETECTED"));
        assertTrue(reportStr.contains("Unclosed Streams"));
    }

    @Test
    void testNullInputsAreIgnored() {
        assertDoesNotThrow(() -> {
            detector.recordStreamOpened(null, "test");
            detector.recordStreamClosed(null, "test");
        });
    }

    @Test
    void testEnableDisableLifecycle() {
        Closeable stream1 = new ByteArrayInputStream(new byte[0]);
        Closeable stream2 = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream1, "stream-1");
        detector.disable();
        
        detector.recordStreamOpened(stream2, "stream-2");
        
        detector.enable();
        detector.recordStreamClosed(stream1, "stream-1");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        // stream-1 was opened and closed, stream-2 was not recorded (disabled when opened)
        // So stream-1 is properly closed, no issues
        assertFalse(report.hasIssues());
    }

    @Test
    void testSummaryStatisticsAreCorrect() {
        Closeable stream1 = new ByteArrayInputStream(new byte[0]);
        Closeable stream2 = new ByteArrayInputStream(new byte[0]);

        detector.recordStreamOpened(stream1, "stream-1");
        detector.recordStreamOpened(stream2, "stream-2");
        detector.recordStreamClosed(stream1, "stream-1");

        StreamClosingDetector.StreamClosingReport report = detector.analyze();

        assertEquals(2, report.totalOpened);
        assertEquals(1, report.totalClosed);
    }
}
