package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimpleDateFormatDetector.
 */
public class SimpleDateFormatDetectorTest {

    /** Runs {@code body} on a second thread and waits for it, so two threads touch the subject. */
    private static void onAnotherThread(Runnable body) {
        Thread other = new Thread(body, "second-user");
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void aParseErrorOnOneThreadIsNotPotentialDataCorruption() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
        detector.registerFormatter(format, "single");

        detector.recordParse(format, "single");
        // One thread, one bad input string. A ParseException here is the input, not a race.
        detector.recordError(format, "single", "ParseException");

        assertTrue(detector.analyze().formattingErrors.isEmpty(),
            "one thread had sole use of this formatter, so nothing could have corrupted its "
                + "internal state: " + detector.analyze().formattingErrors);
    }

    @Test
    void testSingleThreadFormatterUsage() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        
        detector.registerFormatter(sdf, "single-thread-formatter");
        
        sdf.format(new java.util.Date());
        detector.recordFormat(sdf, "single-thread-formatter");
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single thread usage should not report issues");
    }

    @Test
    void testSharedFormatterDetection() throws InterruptedException {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        
        detector.registerFormatter(sdf, "shared-formatter");
        
        // Simulate multiple threads accessing the same formatter
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                sdf.format(new java.util.Date());
                detector.recordFormat(sdf, "shared-formatter");
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                sdf.format(new java.util.Date());
                detector.recordFormat(sdf, "shared-formatter");
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect shared formatter access");
        assertFalse(report.sharedFormatters.isEmpty(), "Should report shared formatters");
    }

    @Test
    void testParseTracking() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        
        detector.registerFormatter(sdf, "parse-formatter");
        
        try {
            sdf.parse("2024-01-01");
        } catch (java.text.ParseException e) {
            // Ignore
        }
        detector.recordParse(sdf, "parse-formatter");
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.formatterActivity.get("parse-formatter").contains("parse: 1"),
                   "Should track parse operations");
    }

    @Test
    void testErrorTracking() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        detector.registerFormatter(sdf, "error-formatter");

        detector.recordFormat(sdf, "error-formatter");
        // Two threads sharing one SimpleDateFormat: the shape in which a parse error is evidence
        // of corrupted internal state rather than of a bad input string (#501).
        onAnotherThread(() -> detector.recordError(sdf, "error-formatter", "ParseException"));

        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.formattingErrors.isEmpty(), "Should track errors");
    }

    @Test
    void testErrorTypeSurfacedInReport() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        detector.registerFormatter(sdf, "typed-error-formatter");

        detector.recordFormat(sdf, "typed-error-formatter");
        onAnotherThread(() -> detector.recordError(sdf, "typed-error-formatter", "ParseException"));

        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();

        assertFalse(report.formattingErrors.isEmpty(), "Should track errors");
        assertTrue(report.formattingErrors.get(0).contains("ParseException"),
                   "Error type should be surfaced in the report");
    }

    @Test
    void testErrorOnUnregisteredFormatterAutoRegisters() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        // Note: no registerFormatter() call - recordError must auto-register
        detector.recordFormat(sdf, "unregistered-formatter");
        onAnotherThread(() ->
                detector.recordError(sdf, "unregistered-formatter", "IllegalArgumentException"));

        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();

        assertTrue(report.hasIssues(), "Error on unregistered formatter should not be silently dropped");
        assertFalse(report.formattingErrors.isEmpty(), "Should report the auto-registered formatter's error");
        assertTrue(report.formattingErrors.get(0).contains("IllegalArgumentException"),
                   "Error type should still be captured for an auto-registered formatter");
    }

    @Test
    void testMethodBreakdown() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        
        detector.registerFormatter(sdf, "multi-method-formatter");
        
        detector.recordFormat(sdf, "multi-method-formatter");
        detector.recordFormat(sdf, "multi-method-formatter");
        detector.recordParse(sdf, "multi-method-formatter");
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        assertNotNull(report);
        // Single thread, no issues expected
        assertFalse(report.hasIssues(), "Single thread should not report issues");
    }

    @Test
    void testNullSafety() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        
        // Should not throw on null inputs
        detector.registerFormatter(null, "null-formatter");
        detector.recordFormat(null, "null");
        detector.recordParse(null, "null");
        detector.recordError(null, "null", "error");
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        SimpleDateFormatDetector detector = new SimpleDateFormatDetector();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        
        detector.registerFormatter(sdf, "test-formatter");
        
        // Simulate shared access
        Thread t1 = new Thread(() -> {
            sdf.format(new java.util.Date());
            detector.recordFormat(sdf, "test-formatter");
        });
        
        Thread t2 = new Thread(() -> {
            sdf.format(new java.util.Date());
            detector.recordFormat(sdf, "test-formatter");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SIMPLE DATE FORMAT ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Shared Formatter Instances"), "Report should mention shared formatters");
    }
}
