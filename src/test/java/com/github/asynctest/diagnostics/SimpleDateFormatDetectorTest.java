package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimpleDateFormatDetector.
 */
public class SimpleDateFormatDetectorTest {

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
        detector.recordError(sdf, "error-formatter", "ParseException");
        
        SimpleDateFormatDetector.SimpleDateFormatReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.formattingErrors.isEmpty(), "Should track errors");
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
