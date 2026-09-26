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
    void threadMadeDaemonAfterRecordingIsNotFlagged() throws Exception {
        // #760: the documented order is record, then start, and setDaemon(true) may come between.
        var d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            try { release.await(); } catch (InterruptedException ignored) { }
        }, "daemon-after-record");
        t.setDaemon(false);
        d.recordThread(t, "daemon-after-record");
        t.setDaemon(true);
        t.start();
        try {
            assertTrue(t.isAlive() && t.isDaemon(), "precondition: alive and daemon at analysis");
            assertFalse(d.analyze().hasIssues(),
                    "a thread that is daemon when analysed cannot block JVM exit; the flag read at "
                            + "recording time is stale");
        } finally {
            release.countDown();
            t.join();
        }
    }

    @Test
    void threadMadeNonDaemonAfterRecordingIsFlagged() throws Exception {
        var d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            try { release.await(); } catch (InterruptedException ignored) { }
        }, "non-daemon-after-record");
        t.setDaemon(true);
        d.recordThread(t, "non-daemon-after-record");
        t.setDaemon(false);
        t.start();
        try {
            var report = d.analyze();
            assertTrue(report.hasIssues(),
                    "a thread that is non-daemon and alive when analysed blocks JVM exit, whatever "
                            + "its flag was when it was recorded");
            assertTrue(report.violations.get(0).contains("non-daemon"), report.violations.get(0));
        } finally {
            release.countDown();
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

    @Test
    void aLeakedThreadIsMediumAtTheGate() throws Exception {
        DaemonThreadHygieneDetector d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> { try { release.await(); } catch (InterruptedException ignored) { } }, "leak");
        t.setDaemon(false);
        t.start();
        try {
            d.recordThread(t, "leak");
            String report = d.analyze().toString();
            assertEquals(IssueSeverity.MEDIUM, DetectorDefaultSeverity.of("DaemonThreadHygieneDetector", report),
                "the Violation says MEDIUM but the text path, which the gate reads, said nothing and fell to HIGH");
        } finally {
            release.countDown();
            t.join();
        }
    }

    @Test
    void aWovenStartOfAThreadThatOnlyInheritedDaemonIsFlagged() throws Exception {
        DaemonThreadHygieneDetector d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> created = new java.util.concurrent.atomic.AtomicReference<>();
        // The runner's workers are daemon (#479), so a thread built on one inherits the flag
        // without anybody deciding it. recordObservedStart is what the woven start() calls.
        Thread daemonParent = new Thread(() -> {
            Thread t = new Thread(() -> {
                try { release.await(); } catch (InterruptedException ignored) { }
            }, "inherited-daemon");
            created.set(t);
            d.recordObservedStart(t);
            t.start();
        }, "daemon-parent");
        daemonParent.setDaemon(true);
        daemonParent.start();
        daemonParent.join();
        try {
            assertTrue(created.get().isDaemon(), "precondition: the flag was inherited");
            var report = d.analyze();
            assertTrue(report.hasIssues(),
                "a started thread with no setDaemon decision must be flagged while alive, "
                        + "whatever flag it inherited");
            assertTrue(report.violations.get(0).contains("inherited the flag"),
                "the message must not call an inherited-daemon thread non-daemon: "
                        + report.violations.get(0));
        } finally {
            release.countDown();
            created.get().join();
        }
    }

    @Test
    void aWovenStartAfterAWovenSetDaemonTrueIsNotFlagged() throws Exception {
        DaemonThreadHygieneDetector d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> created = new java.util.concurrent.atomic.AtomicReference<>();
        Thread daemonParent = new Thread(() -> {
            Thread t = new Thread(() -> {
                try { release.await(); } catch (InterruptedException ignored) { }
            }, "explicit-daemon");
            se.deversity.asynctest.AgentThreadHooks.threadSetDaemon(t, true); // the woven setDaemon(true)
            created.set(t);
            d.recordObservedStart(t);
            t.start();
        }, "daemon-parent");
        daemonParent.setDaemon(true);
        daemonParent.start();
        daemonParent.join();
        try {
            assertFalse(d.analyze().hasIssues(),
                "an explicit setDaemon(true) is the decision the rule asks for");
        } finally {
            release.countDown();
            created.get().join();
        }
    }

    @Test
    void aManualRecordingOfAnInheritedDaemonThreadKeepsTheOldReading() throws Exception {
        DaemonThreadHygieneDetector d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> created = new java.util.concurrent.atomic.AtomicReference<>();
        Thread daemonParent = new Thread(() -> {
            Thread t = new Thread(() -> {
                try { release.await(); } catch (InterruptedException ignored) { }
            }, "recorded-daemon");
            created.set(t);
            d.recordThread(t, "recorded-daemon");
            t.start();
        }, "daemon-parent");
        daemonParent.setDaemon(true);
        daemonParent.start();
        daemonParent.join();
        try {
            assertFalse(d.analyze().hasIssues(),
                "without a woven start the flag cannot say whether anybody decided it, so "
                        + "recordThread must not start reporting every daemon thread");
        } finally {
            release.countDown();
            created.get().join();
        }
    }

    @Test
    void aWovenStartOfAThreadRecordedByHandIsJudgedByTheDecision() throws Exception {
        DaemonThreadHygieneDetector d = new DaemonThreadHygieneDetector();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> created = new java.util.concurrent.atomic.AtomicReference<>();
        Thread daemonParent = new Thread(() -> {
            Thread t = new Thread(() -> {
                try { release.await(); } catch (InterruptedException ignored) { }
            }, "recorded-then-started");
            created.set(t);
            d.recordThread(t, "by-hand");
            d.recordObservedStart(t);
            t.start();
        }, "daemon-parent");
        daemonParent.setDaemon(true);
        daemonParent.start();
        daemonParent.join();
        try {
            var report = d.analyze();
            assertTrue(report.hasIssues(),
                "recording by hand first must not let the inherited flag excuse a woven start");
            assertTrue(report.violations.get(0).contains("by-hand"),
                "the first registration's label is kept: " + report.violations.get(0));
        } finally {
            release.countDown();
            created.get().join();
        }
    }
}
