package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotifyWithoutMonitorDetectorTest {

    @Test
    void cleanWhenNoAttempts() {
        var d = new NotifyWithoutMonitorDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void heldMonitorIsNotFlagged() {
        var d = new NotifyWithoutMonitorDetector();
        Object mutex = new Object();
        synchronized (mutex) {
            d.recordNotifyAttempt(mutex, "queue");
        }
        assertFalse(d.analyze().hasIssues(),
                "Holding the monitor at attempt time is legal — no flag");
    }

    @Test
    void unheldMonitorIsFlagged() {
        var d = new NotifyWithoutMonitorDetector();
        Object mutex = new Object();
        d.recordNotifyAttempt(mutex, "queue");
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("queue"));
        assertTrue(msg.contains("IllegalMonitorStateException"));
        assertTrue(msg.contains("wait()-ers"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("NotifyWithoutMonitor", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void multipleUnheldCallsCollectAll() {
        var d = new NotifyWithoutMonitorDetector();
        Object a = new Object();
        Object b = new Object();
        d.recordNotifyAttempt(a, "queue-a");
        d.recordNotifyAttempt(b, "queue-b");
        d.recordNotifyAttempt(a, "queue-a");
        var report = d.analyze();
        assertEquals(3, report.violations.size(),
                "Each illegal call is recorded — the user sees the full timeline");
    }

    @Test
    void nullMonitorIsIgnored() {
        var d = new NotifyWithoutMonitorDetector();
        d.recordNotifyAttempt(null, "doesnt-matter");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void labelFallbackUsesIdentityHash() {
        var d = new NotifyWithoutMonitorDetector();
        Object mutex = new Object();
        d.recordNotifyAttempt(mutex, null); // no label
        String msg = d.analyze().violations.get(0);
        // Fallback label format: SimpleName@identityHash
        assertTrue(msg.contains("Object@"),
                "Null label must fall back to ClassName@identityHash, got: " + msg);
    }

    @Test
    void concurrentAttemptsAreRecordedSafely() throws Exception {
        var d = new NotifyWithoutMonitorDetector();
        Object mutex = new Object();
        int threads = 8;
        int callsPerThread = 50;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < callsPerThread; j++) {
                    d.recordNotifyAttempt(mutex, "race");
                }
            });
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();
        assertEquals(threads * callsPerThread, d.analyze().violations.size(),
                "All concurrent attempts must be recorded — no lost events");
    }
}
