package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SharedCharsetCoderDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedCharsetCoderDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() {
        var d = new SharedCharsetCoderDetector();
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        for (int i = 0; i < 5; i++) {
            d.recordAccess(encoder, "encode", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedEncoderAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedCharsetCoderDetector();
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        d.recordAccess(encoder, "encode", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(encoder, "encode", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("CharsetEncoder"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SharedCharsetCoder", report.structuredViolations.get(0).detector());
        assertEquals("CharsetEncoder", report.structuredViolations.get(0).attributes().get("kind"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void sharedDecoderAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedCharsetCoderDetector();
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        d.recordAccess(decoder, "decode", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(decoder, "decode", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("CharsetDecoder"));
        assertEquals("CharsetDecoder", report.structuredViolations.get(0).attributes().get("kind"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedCharsetCoderDetector();
        CharsetEncoder a = StandardCharsets.UTF_8.newEncoder();
        CharsetEncoder b = StandardCharsets.UTF_8.newEncoder();
        d.recordAccess(a, "encode", Thread.currentThread());
        d.recordAccess(b, "encode", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(a, "encode", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void reportDescribesHazardAndFix() throws Exception {
        var d = new SharedCharsetCoderDetector();
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        d.recordAccess(encoder, "encode", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(encoder, "reset", Thread.currentThread()));
        t.start();
        t.join();
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("SHARED CHARSET ENCODER/DECODER DETECTED"));
        assertTrue(rendered.contains("IllegalStateException"));
        assertTrue(rendered.contains("newEncoder()") || rendered.contains("newDecoder()"));
        assertTrue(rendered.contains("ThreadLocal"));
    }

    @Test
    void analyzeIsIdempotentAndSideEffectFree() throws Exception {
        var d = new SharedCharsetCoderDetector();
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        d.recordAccess(encoder, "encode", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(encoder, "encode", Thread.currentThread()));
        t.start();
        t.join();
        var first = d.analyze();
        var second = d.analyze();
        assertEquals(first.violations.size(), second.violations.size());
        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedCharsetCoderDetector();
        d.recordAccess((CharsetEncoder) null, "encode", Thread.currentThread());
        d.recordAccess((CharsetDecoder) null, "decode", Thread.currentThread());
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        d.recordAccess(encoder, "encode", null);
        assertFalse(d.analyze().hasIssues());
    }
}
