package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DaemonThreadHygieneDetectorTest {

    @Test
    void cleanWhenNoThreadsRecorded() {
        var d = new DaemonThreadHygieneDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void daemonThreadIsNotFlagged() throws Exception {
        var d = new DaemonThreadHygieneDetector();
        Thread t = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }, "daemon-ok");
        t.setDaemon(true);
        d.recordThread(t, "daemon-ok");
        t.start();
        try {
            assertFalse(d.analyze().hasIssues(),
                    "Daemon thread must not be flagged — it does not block JVM exit");
        } finally {
            t.interrupt();
            t.join();
        }
    }

    @Test
    void nonDaemonStillAliveIsFlagged() throws Exception {
        var d = new DaemonThreadHygieneDetector();
        Thread t = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }, "leak-thread");
        // Intentionally NOT setDaemon — this is the case we want to catch.
        d.recordThread(t, "leak-thread");
        t.start();
        try {
            var report = d.analyze();
            assertTrue(report.hasIssues(),
                    "Non-daemon thread still alive at analysis time must be flagged");
            String msg = report.violations.get(0);
            assertTrue(msg.contains("leak-thread"));
            assertTrue(msg.contains("non-daemon"));
            assertTrue(msg.contains("still alive"));
            // Structured Violation present and consistent
            assertEquals(1, report.structuredViolations.size());
            assertEquals("DaemonThreadHygiene", report.structuredViolations.get(0).detector());
            assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
            assertEquals("leak-thread", report.structuredViolations.get(0).attributes().get("label"));
            assertEquals(Boolean.TRUE, report.structuredViolations.get(0).attributes().get("stillAlive"));
        } finally {
            t.interrupt();
            t.join();
        }
    }

    @Test
    void nullThreadIsIgnored() {
        var d = new DaemonThreadHygieneDetector();
        d.recordThread(null, "ignored");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void firstRegistrationWinsForLabel() throws Exception {
        var d = new DaemonThreadHygieneDetector();
        Thread t = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }, "thread-x");
        d.recordThread(t, "first-label");
        d.recordThread(t, "second-label");
        t.start();
        try {
            String msg = d.analyze().violations.get(0);
            assertTrue(msg.contains("first-label"),
                    "Sticky-on-first-registration like SharedMessageDigestDetector");
            assertFalse(msg.contains("second-label"));
        } finally {
            t.interrupt();
            t.join();
        }
    }

    @Test
    void terminatedNonDaemonThreadIsNotFlagged() throws Exception {
        var d = new DaemonThreadHygieneDetector();
        Thread t = new Thread(() -> {}, "quick-exit");
        d.recordThread(t, "quick-exit");
        t.start();
        t.join(); // ensure terminated before analyze()
        // After termination it's not alive AND no longer reachable; not flagged.
        assertFalse(d.analyze().hasIssues(),
                "A non-daemon thread that has cleanly terminated does not block JVM exit");
    }
}
