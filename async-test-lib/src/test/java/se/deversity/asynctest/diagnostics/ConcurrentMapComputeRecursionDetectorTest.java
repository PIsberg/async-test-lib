package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentMapComputeRecursionDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new ConcurrentMapComputeRecursionDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForNonRecursiveCompute() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key", t, "cache");
        d.recordComputeEnd(map, "key", t);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsRecursiveComputeOnSameKey() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key", t, "cache");
        d.recordComputeStart(map, "key", t, "cache"); // re-entry on same (map, key, thread)
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().recursions.get(0).contains("cache"));
        assertTrue(d.analyze().recursions.get(0).contains("key"));
    }

    @Test
    void testNoIssueForDifferentKeys() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key-A", t, "cache");
        d.recordComputeStart(map, "key-B", t, "cache"); // different key — ok
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueForDifferentMaps() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> m1 = new ConcurrentHashMap<>();
        Map<String, String> m2 = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(m1, "key", t, "map1");
        d.recordComputeStart(m2, "key", t, "map2"); // different map — ok
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueAfterEnd() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key", t, "cache");
        d.recordComputeEnd(map, "key", t); // slot cleared
        d.recordComputeStart(map, "key", t, "cache"); // second call is not recursive
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new ConcurrentMapComputeRecursionDetector();
        assertDoesNotThrow(() -> {
            d.recordComputeStart(null, "k", Thread.currentThread(), "m");
            d.recordComputeStart(new ConcurrentHashMap<>(), null, Thread.currentThread(), "m");
            d.recordComputeStart(new ConcurrentHashMap<>(), "k", null, "m");
            d.recordComputeEnd(null, "k", Thread.currentThread());
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "k", t, "m");
        d.recordComputeStart(map, "k", t, "m");
        String s = d.analyze().toString();
        assertTrue(s.contains("CONCURRENT MAP COMPUTE RECURSION"));
        assertTrue(s.contains("Fix"));
    }
}
