package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class ThreadLocalRandomMisuseDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new ThreadLocalRandomMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void useOnObtainingThreadIsNotFlagged() {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        d.recordObtain(rng, "local-rng", Thread.currentThread());
        for (int i = 0; i < 5; i++) {
            d.recordUse(rng, Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void cachedReferenceUsedFromAnotherThreadIsFlagged() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        // Simulate the bug: capture current() on this thread, share the reference.
        ThreadLocalRandom shared = ThreadLocalRandom.current();
        d.recordObtain(shared, "cached-rng", Thread.currentThread());
        Thread t = new Thread(() -> d.recordUse(shared, Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("cached-rng"));
        assertTrue(msg.contains("per-thread"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("ThreadLocalRandomMisuse", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("misusingThreadCount"));
    }

    @Test
    void useWithoutObtainIsIgnored() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // never recorded as obtained
        Thread t = new Thread(() -> d.recordUse(rng, Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new ThreadLocalRandomMisuseDetector();
        d.recordObtain(null, "label", Thread.currentThread());
        d.recordObtain(ThreadLocalRandom.current(), "label", null);
        d.recordUse(null, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }
}
