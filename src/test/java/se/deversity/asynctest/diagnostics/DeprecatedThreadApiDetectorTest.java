package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DeprecatedThreadApiDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new DeprecatedThreadApiDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsStop() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.stop", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Thread.stop"));
    }

    @Test
    void testDetectsSuspend() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.suspend", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testDetectsResume() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.resume", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testDetectsDestroy() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.destroy", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testDetectsCountStackFrames() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.countStackFrames", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testMultipleUsagesFromMultipleThreads() throws Exception {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.stop", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordApiUse("Thread.suspend", Thread.currentThread()));
        t2.start(); t2.join();
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNullSafety() {
        var d = new DeprecatedThreadApiDetector();
        assertDoesNotThrow(() -> d.recordApiUse(null, Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordApiUse("Thread.stop", null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new DeprecatedThreadApiDetector();
        d.recordApiUse("Thread.stop", Thread.currentThread());
        String s = d.analyze().toString();
        assertTrue(s.contains("DEPRECATED THREAD API"));
        assertTrue(s.contains("Fix"));
    }

    @Test
    void testDeprecatedApisSetIsComplete() {
        assertTrue(DeprecatedThreadApiDetector.DEPRECATED_APIS.contains("Thread.stop"));
        assertTrue(DeprecatedThreadApiDetector.DEPRECATED_APIS.contains("Thread.suspend"));
        assertTrue(DeprecatedThreadApiDetector.DEPRECATED_APIS.contains("Thread.resume"));
        assertTrue(DeprecatedThreadApiDetector.DEPRECATED_APIS.contains("Thread.destroy"));
        assertTrue(DeprecatedThreadApiDetector.DEPRECATED_APIS.contains("Thread.countStackFrames"));
    }
}
