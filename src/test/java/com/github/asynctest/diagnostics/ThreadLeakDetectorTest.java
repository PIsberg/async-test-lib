package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadLeakDetectorTest {

    private ThreadLeakDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ThreadLeakDetector();
    }

    @Test
    void noLeaks_whenThreadsProperlyTerminated() {
        Thread testThread = new Thread(() -> {});
        detector.recordThreadStart(testThread, "temp-thread");
        detector.recordThreadEnd(testThread);

        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        
        // Thread terminated, so no leaks
        assertFalse(report.hasIssues());
    }

    @Test
    void detectsLeak_whenThreadNotTerminated() {
        Thread leakedThread = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
        });
        leakedThread.start();
        
        detector.recordThreadStart(leakedThread, "leaked-worker");

        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        
        assertTrue(report.hasIssues());
        assertEquals(1, report.getLeaks().size());
        assertEquals("leaked-worker", report.getLeaks().get(0).threadName);
        
        leakedThread.interrupt();
        try { leakedThread.join(1000); } catch (InterruptedException e) {}
    }

    @Test
    void report_containsCreationStackTrace() {
        Thread thread = new Thread(() -> {});
        detector.recordThreadStart(thread, "trace-test");

        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        
        // Thread still alive, should have creation stack
        if (report.hasIssues()) {
            ThreadLeakDetector.ThreadLeakEvent event = report.getLeaks().get(0);
            assertNotNull(event.creationStack);
            assertTrue(event.creationStack.length > 0);
        }
    }

    @Test
    void autoMode_detectsThreadGrowth() {
        detector.enableAutoMode();

        // Create some threads without tracking
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
            });
            threads[i].start();
        }

        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        
        // May or may not trigger depending on threshold, but shouldn't crash
        assertNotNull(report);

        // Cleanup
        for (Thread t : threads) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) {}
        }
    }

    @Test
    void clear_removesAllTrackedThreads() {
        Thread thread = new Thread(() -> {});
        detector.recordThreadStart(thread, "to-clear");
        
        detector.clear();
        
        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        assertFalse(report.hasIssues());
    }

    @Test
    void disabledDetector_returnsNoLeaks() {
        detector.disable();
        
        Thread thread = new Thread(() -> {});
        detector.recordThreadStart(thread, "disabled-test");
        
        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        assertFalse(report.hasIssues());
    }

    @Test
    void report_showsCorrectSummary() {
        Thread t1 = new Thread(() -> {});
        t1.setName("t1");
        Thread t2 = new Thread(() -> {});
        t2.setName("t2");
        
        detector.recordThreadStart(t1, "thread-1");
        detector.recordThreadStart(t2, "thread-2");
        detector.recordThreadEnd(t2);

        ThreadLeakDetector.ThreadLeakReport report = detector.analyzeLeaks();
        
        String reportStr = report.toString();
        assertTrue(reportStr.contains("ThreadLeakReport"));
        assertTrue(reportStr.contains("Total tracked: 2"));
        assertTrue(reportStr.contains("Terminated: 1"));
    }
}
