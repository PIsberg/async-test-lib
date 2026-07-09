package se.deversity.asynctest.diagnostics;

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
        vt.join(5_000);
        assertFalse(vt.isAlive(), "virtual thread did not finish in time");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Expected CPU-bound violation on virtual thread");
        assertFalse(report.getViolations().isEmpty());
        assertTrue(report.getViolations().get(0).contains("slow-task"));
    }

    @Test
    void virtualThread_yieldPointResetsTimer_noViolation() throws Exception {
        // Local detector with a wide threshold: the pre-yield spin (300ms) is guaranteed
        // to exceed it, so the yield reset is what prevents the violation. Ending the
        // task right after the yield leaves the full 250ms threshold as scheduling-stall
        // headroom on loaded CI runners (segment time is wall-clock, not CPU time).
        var yieldDetector = new VirtualThreadCpuBoundTaskDetector(250L);
        Thread vt = Thread.ofVirtual().start(() -> {
            String id = yieldDetector.recordTaskStart("io-mixed-task");
            try {
                // CPU burst longer than the threshold — would violate without the yield
                long deadline = System.nanoTime() + 300_000_000L; // 300ms
                while (System.nanoTime() < deadline) { /* spin */ }
                // Yield point resets the segment timer; end immediately afterwards
                yieldDetector.recordYieldPoint(id);
            } finally {
                yieldDetector.recordTaskEnd(id);
            }
        });
        vt.join(5_000);
        assertFalse(vt.isAlive(), "virtual thread did not finish in time");

        var report = yieldDetector.analyze();
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
        pt.join(5_000);
        assertFalse(pt.isAlive(), "platform thread did not finish in time");

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Platform thread tasks should not be flagged");
    }

    @Test
    void multipleVirtualThreads_onlyLongOnesViolate() throws Exception {
        // Local detector with a wide threshold: "fast" must NOT violate, and its
        // start/end are adjacent calls — with the 5ms threshold a single scheduling
        // stall between them on a loaded CI runner would flag it and break the
        // exactly-one-violation assertion below.
        var multiDetector = new VirtualThreadCpuBoundTaskDetector(250L);
        Thread fast = Thread.ofVirtual().start(() -> {
            String id = multiDetector.recordTaskStart("fast");
            multiDetector.recordTaskEnd(id);
        });
        Thread slow = Thread.ofVirtual().start(() -> {
            String id = multiDetector.recordTaskStart("slow");
            try {
                long deadline = System.nanoTime() + 300_000_000L; // 300ms > threshold
                while (System.nanoTime() < deadline) { /* spin */ }
            } finally {
                multiDetector.recordTaskEnd(id);
            }
        });
        fast.join(5_000);
        slow.join(5_000);
        assertFalse(fast.isAlive(), "fast thread did not finish in time");
        assertFalse(slow.isAlive(), "slow thread did not finish in time");

        var report = multiDetector.analyze();
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
        vt.join(5_000);
        assertFalse(vt.isAlive(), "virtual thread did not finish in time");

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
