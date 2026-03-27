package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VolatileArrayDetector.
 */
public class VolatileArrayDetectorTest {

    @Test
    void testSingleThreadArrayAccess() {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        int[] array = new int[10];

        detector.registerArray(array, "singleThreadArray", int.class);
        detector.recordElementWrite(array, 0, "singleThreadArray");
        detector.recordElementRead(array, 0, "singleThreadArray");

        VolatileArrayDetector.VolatileArrayReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single thread access should not report issues");
    }

    @Test
    void testMultiThreadArrayAccessDetection() throws Exception {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        int[] array = new int[10];

        detector.registerArray(array, "multiThreadArray", int.class);

        Thread t1 = new Thread(() -> {
            detector.recordElementWrite(array, 0, "multiThreadArray");
            array[0] = 42;
        });

        Thread t2 = new Thread(() -> {
            detector.recordElementWrite(array, 0, "multiThreadArray");
            array[0] = 100;
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        VolatileArrayDetector.VolatileArrayReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect multi-thread array access");
    }

    @Test
    void testReportToString() {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        int[] array = new int[5];

        detector.registerArray(array, "testArray", int.class);
        detector.recordElementWrite(array, 0, "testArray");

        VolatileArrayDetector.VolatileArrayReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("VOLATILE ARRAY ISSUES DETECTED") || 
                   reportStr.contains("No volatile array issues"), 
                   "Report should have proper format");
    }

    @Test
    void testNullSafety() {
        VolatileArrayDetector detector = new VolatileArrayDetector();

        // Should not throw on null inputs
        detector.recordElementWrite(null, 0, "null-array");
        detector.recordElementRead(null, 0, "null-array");

        VolatileArrayDetector.VolatileArrayReport report = detector.analyze();
        assertNotNull(report);
    }
}
