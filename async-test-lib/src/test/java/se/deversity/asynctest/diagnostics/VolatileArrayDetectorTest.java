package se.deversity.asynctest.diagnostics;

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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("two threads sharing a name are still two threads")
    void twoThreadsWithTheSameNameAreCountedSeparately() throws Exception {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        int[] shared = new int[] {0};
        detector.registerArray(shared, "shared-array", int.class);

        // The detector keyed each access on Thread.getName(). Names are not identities: two
        // platform threads can share one, and an unnamed virtual thread has the empty string -
        // which is what @AsyncTest's default workers are. Two distinct threads then collapsed
        // into a single key, "more than one thread wrote this array" could never reach two, and
        // the detector was silent under exactly the sharing it exists to report.
        //
        // Both threads below are deliberately given the SAME name, which is the reproduction.
        Runnable worker = () -> detector.recordElementWrite(shared, 0, "shared-array");
        Thread first = new Thread(worker, "same-name");
        Thread second = new Thread(worker, "same-name");
        first.start();
        first.join();
        second.start();
        second.join();

        assertTrue(detector.analyze().hasIssues(),
            "Two different threads wrote element 0 of one shared array - that is the finding, "
            + "and it does not stop being one because the threads happen to share a name. "
            + "Count threads by threadId(), which is unique for the life of the JVM, not by "
            + "getName(), which the caller chooses and may not set at all.");
    }
}
