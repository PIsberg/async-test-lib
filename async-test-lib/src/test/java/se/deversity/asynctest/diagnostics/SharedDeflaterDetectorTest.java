package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

class SharedDeflaterDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedDeflaterDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new SharedDeflaterDetector();
        var deflater = new Deflater();
        try {
            for (int i = 0; i < 5; i++) {
                d.recordAccess(deflater, "sole-thread", Thread.currentThread());
            }
            assertFalse(d.analyze().hasIssues());
        } finally {
            deflater.end();
        }
    }

    @Test
    void sharedDeflaterAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedDeflaterDetector();
        var deflater = new Deflater();
        try {
            d.recordAccess(deflater, "response-gzip", Thread.currentThread());
            Thread t = new Thread(() -> d.recordAccess(deflater, "response-gzip", Thread.currentThread()));
            t.start();
            t.join();
            var report = d.analyze();
            assertTrue(report.hasIssues());
            String msg = report.violations.get(0);
            assertTrue(msg.contains("response-gzip"));
            assertTrue(msg.contains("2 threads"));
            assertTrue(msg.contains("Deflater"));
            assertEquals(1, report.structuredViolations.size());
            assertEquals("SharedDeflater", report.structuredViolations.get(0).detector());
            assertEquals("Deflater", report.structuredViolations.get(0).attributes().get("kind"));
            assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        } finally {
            deflater.end();
        }
    }

    @Test
    void sharedInflaterAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedDeflaterDetector();
        var inflater = new Inflater();
        try {
            d.recordAccess(inflater, "request-gunzip", Thread.currentThread());
            Thread t = new Thread(() -> d.recordAccess(inflater, "request-gunzip", Thread.currentThread()));
            t.start();
            t.join();
            var report = d.analyze();
            assertTrue(report.hasIssues());
            assertTrue(report.violations.get(0).contains("Inflater"));
            assertEquals("Inflater", report.structuredViolations.get(0).attributes().get("kind"));
        } finally {
            inflater.end();
        }
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedDeflaterDetector();
        var a = new Deflater();
        var b = new Deflater();
        try {
            d.recordAccess(a, "def-a", Thread.currentThread());
            d.recordAccess(b, "def-b", Thread.currentThread());
            Thread t = new Thread(() -> d.recordAccess(a, "def-a", Thread.currentThread()));
            t.start();
            t.join();
            var report = d.analyze();
            assertEquals(1, report.violations.size());
            assertTrue(report.violations.get(0).contains("def-a"));
        } finally {
            a.end();
            b.end();
        }
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedDeflaterDetector();
        d.recordAccess((Deflater) null, "label", Thread.currentThread());
        d.recordAccess((Inflater) null, "label", Thread.currentThread());
        var deflater = new Deflater();
        try {
            d.recordAccess(deflater, "label", null);
        } finally {
            deflater.end();
        }
        assertFalse(d.analyze().hasIssues());
    }
}
