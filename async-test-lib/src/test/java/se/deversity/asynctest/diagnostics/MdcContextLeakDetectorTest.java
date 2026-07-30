package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class MdcContextLeakDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new MdcContextLeakDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenMdcClearedAtEnd() {
        var d = new MdcContextLeakDetector();
        d.recordTaskStart(Thread.currentThread(), null);
        d.recordTaskEnd(Thread.currentThread(), null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSameKeysAtStartAndEnd() {
        var d = new MdcContextLeakDetector();
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("requestId", "123");
        d.recordTaskStart(Thread.currentThread(), mdc);
        d.recordTaskEnd(Thread.currentThread(), mdc);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsLeakedKey() {
        var d = new MdcContextLeakDetector();
        d.recordTaskStart(Thread.currentThread(), null);
        Map<String, String> end = new LinkedHashMap<>();
        end.put("requestId", "abc");
        d.recordTaskEnd(Thread.currentThread(), end);
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("requestId"));
    }

    @Test
    void testDetectsMultipleLeakedKeys() {
        var d = new MdcContextLeakDetector();
        d.recordTaskStart(Thread.currentThread(), null);
        Map<String, String> end = new LinkedHashMap<>();
        end.put("requestId", "x");
        end.put("userId", "y");
        d.recordTaskEnd(Thread.currentThread(), end);
        assertTrue(d.analyze().violations.get(0).contains("requestId") ||
                   d.analyze().violations.get(0).contains("userId"));
    }

    @Test
    void testPreExistingKeysNotFlagged() {
        var d = new MdcContextLeakDetector();
        Map<String, String> start = Map.of("existing", "v");
        Map<String, String> end = new LinkedHashMap<>(start);
        end.put("leaked", "x");
        d.recordTaskStart(Thread.currentThread(), start);
        d.recordTaskEnd(Thread.currentThread(), end);
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("leaked"));
        assertFalse(report.violations.get(0).contains("existing"));
    }

    @Test
    void testNullSafety() {
        var d = new MdcContextLeakDetector();
        assertDoesNotThrow(() -> d.recordTaskStart(null, null));
        assertDoesNotThrow(() -> d.recordTaskEnd(null, null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new MdcContextLeakDetector();
        d.recordTaskStart(Thread.currentThread(), null);
        d.recordTaskEnd(Thread.currentThread(), Map.of("k", "v"));
        String s = d.analyze().toString();
        assertTrue(s.contains("MDC CONTEXT LEAK"));
        assertTrue(s.contains("Fix"));
    }
}
