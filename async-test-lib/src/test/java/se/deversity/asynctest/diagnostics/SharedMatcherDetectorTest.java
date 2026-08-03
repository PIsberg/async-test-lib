package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class SharedMatcherDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedMatcherDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedMatcherDetector();
        Matcher m = Pattern.compile("\\d+").matcher("123");
        d.recordAccess(m, "m", Thread.currentThread());
        d.recordAccess(m, "m", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMultiThreadAccess() throws Exception {
        var d = new SharedMatcherDetector();
        Matcher m = Pattern.compile("\\d+").matcher("456");
        d.recordAccess(m, "m", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(m, "m", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("m"));
        assertTrue(d.analyze().violations.get(0).contains("2"));
        assertTrue(d.analyze().violations.get(0).contains("observes sharing, not locks"));
    }

    @Test
    void testSeparateMatchersPerThreadNoIssue() throws Exception {
        var d = new SharedMatcherDetector();
        Pattern p = Pattern.compile("\\w+");
        Matcher m1 = p.matcher("foo");
        Matcher m2 = p.matcher("bar");
        d.recordAccess(m1, "m1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(m2, "m2", Thread.currentThread()));
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelFromClassName() throws Exception {
        var d = new SharedMatcherDetector();
        Matcher m = Pattern.compile(".*").matcher("");
        d.recordAccess(m, null, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(m, null, Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("Matcher"));
    }

    @Test
    void testNullSafety() {
        var d = new SharedMatcherDetector();
        Matcher m = Pattern.compile("x").matcher("x");
        assertDoesNotThrow(() -> {
            d.recordAccess(null, "x", Thread.currentThread());
            d.recordAccess(m, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedMatcherDetector();
        Matcher m = Pattern.compile("a").matcher("a");
        d.recordAccess(m, "m", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(m, "m", Thread.currentThread()));
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED REGEX MATCHER"));
        assertTrue(s.contains("Fix"));
    }
}
