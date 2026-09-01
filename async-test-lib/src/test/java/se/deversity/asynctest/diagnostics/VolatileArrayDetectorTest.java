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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("per-thread arrays sharing a label are not one shared array")
    void distinctArraysUnderOneLabelAreTrackedSeparately() throws Exception {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        ThreadLocal<int[]> confined = ThreadLocal.withInitial(() -> new int[1]);

        // A ThreadLocal<int[]> registered under one stable label in every worker is confined,
        // textbook-correct code. The lookup used to answer by identity OR by name, so the second
        // worker's registration found the first worker's entry and returned early, and every
        // worker's writes then landed in that one entry - six private arrays reported as one
        // array written by six threads. Reporting the confinement pattern is reporting the fix.
        Runnable worker = () -> {
            int[] mine = confined.get();
            detector.registerArray(mine, "buffer", int.class);
            detector.recordElementWrite(mine, 0, "buffer");
        };
        Thread first = new Thread(worker, "worker-1");
        Thread second = new Thread(worker, "worker-2");
        first.start();
        first.join();
        second.start();
        second.join();

        assertFalse(detector.analyze().hasIssues(),
            "Each thread wrote only its own array, so no array was ever touched by two threads. "
            + "Resolving an access by label rather than by identity merges arrays that merely "
            + "share a name, which turns the standard per-thread-buffer pattern into a finding.");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("one array under one label is still reported")
    void oneSharedArrayUnderOneLabelStillFires() throws Exception {
        VolatileArrayDetector detector = new VolatileArrayDetector();
        int[] shared = new int[1];

        // The other direction of the same fix: preferring identity must not stop the detector
        // seeing a genuinely shared array, which is the case it exists for.
        Runnable worker = () -> {
            detector.registerArray(shared, "buffer", int.class);
            detector.recordElementWrite(shared, 0, "buffer");
        };
        Thread first = new Thread(worker, "worker-1");
        Thread second = new Thread(worker, "worker-2");
        first.start();
        first.join();
        second.start();
        second.join();

        assertTrue(detector.analyze().hasIssues(),
            "One array, two threads, both writing element 0 - the finding is owed.");
    }
}
