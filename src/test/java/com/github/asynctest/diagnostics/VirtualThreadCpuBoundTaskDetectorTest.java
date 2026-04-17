package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadCpuBoundTaskDetectorTest {

    private VirtualThreadCpuBoundTaskDetector detector;

    @BeforeEach
    void setUp() {
        // Use a very short threshold so tests don't need to sleep long
        detector = new VirtualThreadCpuBoundTaskDetector(5L);
    }

    @Test
    void noTasksRecorded_reportHasNoIssues() {
        var report = detector.analyze();
        assertFalse(report.hasIssues());
        assertEquals(0, report.getTotalTasks());
    }

    @Test
    void taskCompletedQuickly_noViolation() {
        // A task that completes well within the threshold on a platform thread
        String id = detector.recordTaskStart("fast-task");
        detector.recordTaskEnd(id);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), report.toString());
        assertEquals(1, report.getTotalTasks());
    }

    @Test
    void virtualThread_taskExceedsThreshold_violation() throws Exception {
        // Run in a virtual thread so isVirtual=true
        Thread vt = Thread.ofVirtual().start(() -> {
            String id = detector.recordTaskStart("slow-task");
            try {
                // Spin for longer than the 5ms threshold
                long deadline = System.nanoTime() + 20_000_000L; // 20ms
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                detector.recordTaskEnd(id);
            }
        });
        vt.join(500);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Expected CPU-bound violation on virtual thread");
        assertFalse(report.getViolations().isEmpty());
        assertTrue(report.getViolations().get(0).contains("slow-task"));
    }

    @Test
    void virtualThread_yieldPointResetsTimer_noViolation() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            String id = detector.recordTaskStart("io-mixed-task");
            try {
                // Record a yield point immediately — simulates blocking I/O
                detector.recordYieldPoint(id);
                // Then do a short CPU burst (< threshold)
                long deadline = System.nanoTime() + 1_000_000L; // 1ms
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                detector.recordTaskEnd(id);
            }
        });
        vt.join(500);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Yield point should have reset the CPU timer: " + report);
    }

    @Test
    void platformThread_longRunning_noViolation() throws InterruptedException {
        // Long-running tasks on platform threads should not be flagged
        Thread pt = Thread.ofPlatform().start(() -> {
            String id = detector.recordTaskStart("platform-task");
            try {
                long deadline = System.nanoTime() + 20_000_000L;
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                detector.recordTaskEnd(id);
            }
        });
        pt.join(500);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Platform thread tasks should not be flagged");
    }

    @Test
    void multipleVirtualThreads_onlyLongOnesViolate() throws Exception {
        Thread fast = Thread.ofVirtual().start(() -> {
            String id = detector.recordTaskStart("fast");
            detector.recordTaskEnd(id);
        });
        Thread slow = Thread.ofVirtual().start(() -> {
            String id = detector.recordTaskStart("slow");
            try {
                long deadline = System.nanoTime() + 20_000_000L;
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                detector.recordTaskEnd(id);
            }
        });
        fast.join(200);
        slow.join(500);

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getViolations().size());
        assertTrue(report.getViolations().get(0).contains("slow"));
        assertEquals(2, report.getTotalTasks());
    }

    @Test
    void report_toStringContainsSummary() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            String id = detector.recordTaskStart("heavy");
            try {
                long deadline = System.nanoTime() + 20_000_000L;
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                detector.recordTaskEnd(id);
            }
        });
        vt.join(500);

        var report = detector.analyze();
        String text = report.toString();
        assertTrue(text.contains("CPU-bound"));
        assertTrue(text.contains("heavy"));
        assertTrue(text.contains("LEARNING"));
    }

    @Test
    void noIssues_toStringContainsNoIssuesMessage() {
        var report = detector.analyze();
        assertTrue(report.toString().contains("No CPU-bound tasks detected"));
    }
}
