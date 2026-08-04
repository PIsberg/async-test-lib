package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class SharedSecureRandomDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedSecureRandomDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new SharedSecureRandomDetector();
        var rng = new SecureRandom();
        for (int i = 0; i < 5; i++) {
            d.recordAccess(rng, "sole-thread", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void multipleThreadAccessIsFlagged() throws Exception {
        var d = new SharedSecureRandomDetector();
        var rng = new SecureRandom();
        d.recordAccess(rng, "shared-rng", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(rng, "shared-rng", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("shared-rng"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("provider-dependent"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SharedSecureRandom", report.structuredViolations.get(0).detector());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("threadCount"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedSecureRandomDetector();
        var a = new SecureRandom();
        var b = new SecureRandom();
        d.recordAccess(a, "rng-a", Thread.currentThread());
        d.recordAccess(b, "rng-b", Thread.currentThread());
        Thread t = new Thread(() -> {
            d.recordAccess(a, "rng-a", Thread.currentThread());
            // b only accessed from main thread → not flagged
        });
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("rng-a"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedSecureRandomDetector();
        d.recordAccess(null, "label", Thread.currentThread());
        d.recordAccess(new SecureRandom(), "label", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportEmbedsAlgorithmAndProvider() throws Exception {
        var d = new SharedSecureRandomDetector();
        var rng = new SecureRandom();
        d.recordAccess(rng, "labelled", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(rng, "labelled", Thread.currentThread()));
        t.start();
        t.join();
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("algorithm="),
                "Report must include the SecureRandom's algorithm name");
        assertTrue(msg.contains("provider="),
                "Report must include the SecureRandom's provider name");
    }

    @Test
    void severityIsMediumBecauseJdkProvidersAreDocumentedSafe() throws Exception {
        var d = new SharedSecureRandomDetector();
        var rng = new SecureRandom();
        d.recordAccess(rng, "shared-rng", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(rng, "shared-rng", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity(),
                "java.security.SecureRandom documents instances as safe for concurrent use, "
                        + "and JDK providers synchronize internally. Sharing one is the common "
                        + "correct idiom; what the detector observes is contention plus a "
                        + "provider-portability risk, not corruption, and HIGH made "
                        + "failOn = HIGH fail builds over documented-safe code.");
    }

    @Test
    void renderedReportCarriesAnExplicitMediumSeverityMarker() throws Exception {
        var d = new SharedSecureRandomDetector();
        var rng = new SecureRandom();
        d.recordAccess(rng, "shared-rng", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(rng, "shared-rng", Thread.currentThread()));
        t.start();
        t.join();
        assertEquals(IssueSeverity.MEDIUM, IssueSeverity.fromReport(d.analyze().toString()),
                "The failOn gate reads severity out of the rendered text "
                        + "(IssueSeverity.fromReport), and an untagged report defaults to "
                        + "HIGH. Without an explicit marker in toString(), the structured "
                        + "MEDIUM above never reaches the gate.");
    }
}
