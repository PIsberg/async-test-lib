package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SharedXmlParserDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedXmlParserDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedXmlParserDetector();
        Object parser = new Object();
        d.recordAccess(parser, "DocumentBuilder", Thread.currentThread());
        d.recordAccess(parser, "DocumentBuilder", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsSharedParser() throws Exception {
        var d = new SharedXmlParserDetector();
        Object parser = new Object();
        d.recordAccess(parser, "SAXParser", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(parser, "SAXParser", Thread.currentThread()));
        t2.start(); t2.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("SAXParser"));
        assertTrue(report.violations.get(0).contains("2"));
    }

    @Test
    void testSeparateParserPerThreadNoIssue() throws Exception {
        var d = new SharedXmlParserDetector();
        Object p1 = new Object();
        Object p2 = new Object();
        d.recordAccess(p1, "Transformer", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(p2, "Transformer", Thread.currentThread()));
        t2.start(); t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelFromClassName() throws Exception {
        var d = new SharedXmlParserDetector();
        Object parser = new Object();
        d.recordAccess(parser, null, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(parser, null, Thread.currentThread()));
        t2.start(); t2.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new SharedXmlParserDetector();
        assertDoesNotThrow(() -> d.recordAccess(null, "DocumentBuilder", Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordAccess(new Object(), "DocumentBuilder", null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedXmlParserDetector();
        Object parser = new Object();
        d.recordAccess(parser, "XPath", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(parser, "XPath", Thread.currentThread()));
        t2.start(); t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED XML PARSER"));
        assertTrue(s.contains("Fix"));
    }
}
