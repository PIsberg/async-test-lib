package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import se.deversity.asynctest.report.Violation;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class SharedByteBufferDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedByteBufferDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadPositionalAccessIsNotFlagged() {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        for (int i = 0; i < 5; i++) {
            d.recordPositionalAccess(buffer, "put");
        }
        d.recordPositionalAccess(buffer, "flip");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedBufferAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        d.recordPositionalAccess(buffer, "put");
        Thread t = new Thread(() -> d.recordPositionalAccess(buffer, "flip"));
        t.start();
        t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("ByteBuffer"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("put"));
        assertTrue(msg.contains("flip"));

        assertEquals(1, report.structuredViolations.size());
        Violation v = report.structuredViolations.get(0);
        assertEquals("SharedByteBuffer", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(buffer.getClass().getSimpleName(), v.attributes().get("kind"));
        assertEquals(2, v.attributes().get("positionalThreadCount"));
    }

    @Test
    void absoluteAccessOnlyIsNotFlaggedEvenFromManyThreads() throws Exception {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        d.recordAbsoluteAccess(buffer, "get(int)");
        Thread t1 = new Thread(() -> d.recordAbsoluteAccess(buffer, "put(int,byte)"));
        Thread t2 = new Thread(() -> d.recordAbsoluteAccess(buffer, "get(int)"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void positionalViolationMentionsAbsoluteAccessAsContext() throws Exception {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        d.recordPositionalAccess(buffer, "put");
        Thread t1 = new Thread(() -> d.recordPositionalAccess(buffer, "flip"));
        t1.start();
        t1.join();
        Thread t2 = new Thread(() -> d.recordAbsoluteAccess(buffer, "get(int)"));
        t2.start();
        t2.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("Also observed absolute operations"));
        assertTrue(msg.contains("get(int)"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedByteBufferDetector();
        var a = ByteBuffer.allocate(8);
        var b = ByteBuffer.allocate(8);
        d.recordPositionalAccess(a, "put");
        d.recordPositionalAccess(b, "put");
        Thread t = new Thread(() -> d.recordPositionalAccess(a, "put"));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains(a.getClass().getSimpleName()));
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedByteBufferDetector();
        d.recordPositionalAccess(null, "flip");
        d.recordAbsoluteAccess(null, "get(int)");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullOperationLabelDoesNotThrow() {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(4);
        assertDoesNotThrow(() -> d.recordPositionalAccess(buffer, null));
        assertDoesNotThrow(() -> d.recordAbsoluteAccess(buffer, null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportToStringExplainsHazardAndFix() throws Exception {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        d.recordPositionalAccess(buffer, "flip");
        Thread t = new Thread(() -> d.recordPositionalAccess(buffer, "rewind"));
        t.start();
        t.join();

        String text = d.analyze().toString();
        assertTrue(text.contains("SHARED BYTE BUFFER DETECTED"));
        assertTrue(text.contains("position/limit/mark"));
        assertTrue(text.contains("BufferUnderflowException") || text.contains("BufferOverflowException"));
        assertTrue(text.contains("duplicate()"));
        assertTrue(text.contains("slice()"));
        assertTrue(text.contains("absolute get(int)/put(int"));
    }

    @Test
    void analyzeIsSideEffectFreeAndIdempotent() throws Exception {
        var d = new SharedByteBufferDetector();
        var buffer = ByteBuffer.allocate(16);
        d.recordPositionalAccess(buffer, "put");
        Thread t = new Thread(() -> d.recordPositionalAccess(buffer, "flip"));
        t.start();
        t.join();

        var first = d.analyze();
        var second = d.analyze();

        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(
                first.structuredViolations.get(0).message(),
                second.structuredViolations.get(0).message());
        assertEquals(
                first.structuredViolations.get(0).attributes(),
                second.structuredViolations.get(0).attributes());
    }
}
