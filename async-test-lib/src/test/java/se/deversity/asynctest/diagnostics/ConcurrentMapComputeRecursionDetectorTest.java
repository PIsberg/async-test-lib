package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentMapComputeRecursionDetectorTest {

    @Test
    void aMappingFunctionThatThrewInAnEarlierRoundIsNotCrossKeyRecursionInTheNext() {
        var d = new ConcurrentMapComputeRecursionDetector();
        java.util.Map<String, String> map = new java.util.concurrent.ConcurrentHashMap<>();
        Thread t = Thread.currentThread();

        // Round one: the mapping function throws, so key "a" is never removed from this
        // thread's scope for this map.
        d.recordComputeStart(map, "a", t, "cache");

        d.markInvocationStart();

        // Round two on the reused pool thread: an unrelated key, computed properly.
        d.recordComputeStart(map, "b", t, "cache");
        d.recordComputeEnd(map, "b", t);

        assertFalse(d.analyze().hasIssues(),
            "key 'a' was left in flight by a round that has since ended; computing key 'b' in "
                + "the next round is not a nested compute on the same map: " + d.analyze());
    }

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

    /**
     * A different key of the same map is reported now, and used to be excused (#343).
     *
     * <p>This assertion is the inverse of the one it replaces. ConcurrentHashMap's contract is
     * "the mapping function must not modify this map", not "must not modify this key", and
     * example 40's own bug is this shape: the excuse made the detector silent on the code its
     * own example ships to demonstrate it.
     */
    @Test
    void testReportsDifferentKeyOnTheSameMap() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key-A", t, "cache");
        d.recordComputeStart(map, "key-B", t, "cache"); // still inside key-A's mapping function
        assertTrue(d.analyze().hasIssues(),
                "a compute on key-B entered while key-A was still being computed modifies the "
                        + "map from inside its own mapping function, which the contract forbids");
    }

    /** The cross-key finding names both keys, or a reader cannot act on it. */
    @Test
    void testDifferentKeyReportNamesBothKeys() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key-A", t, "cache");
        d.recordComputeStart(map, "key-B", t, "cache");
        String report = d.analyze().toString();
        assertTrue(report.contains("key-A"), "the outer key must be named: " + report);
        assertTrue(report.contains("key-B"), "the entered key must be named: " + report);
    }

    /** Sequential computes are not nesting: the first closed before the second opened. */
    @Test
    void testNoIssueForDifferentKeysOneAfterTheOther() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(map, "key-A", t, "cache");
        d.recordComputeEnd(map, "key-A", t);
        d.recordComputeStart(map, "key-B", t, "cache");
        d.recordComputeEnd(map, "key-B", t);
        assertFalse(d.analyze().hasIssues(),
                "nothing was nested here; reporting this would fire on every instrumented map");
    }

    @Test
    void testNoIssueForDifferentMaps() {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> m1 = new ConcurrentHashMap<>();
        Map<String, String> m2 = new ConcurrentHashMap<>();
        Thread t = Thread.currentThread();
        d.recordComputeStart(m1, "key", t, "map1");
        d.recordComputeStart(m2, "key", t, "map2"); // different map, so not this map's contract
        assertFalse(d.analyze().hasIssues(),
                "the contract is per map. A mapping function that consults some other structure "
                        + "is ordinary code, and this is the boundary that keeps the cross-key "
                        + "rule (#343) from firing on it");
    }

    /** Two threads each inside their own compute is contention, not recursion. */
    @Test
    void testNoIssueForTheSameMapOnDifferentThreads() throws InterruptedException {
        var d = new ConcurrentMapComputeRecursionDetector();
        Map<String, String> map = new ConcurrentHashMap<>();
        d.recordComputeStart(map, "key-A", Thread.currentThread(), "cache");
        Thread other = new Thread(() -> {
            d.recordComputeStart(map, "key-B", Thread.currentThread(), "cache");
            d.recordComputeEnd(map, "key-B", Thread.currentThread());
        });
        other.start();
        other.join();
        d.recordComputeEnd(map, "key-A", Thread.currentThread());
        assertFalse(d.analyze().hasIssues(),
                "each thread was inside its own compute; the defect is one thread re-entering, "
                        + "and six threads computing at once is what a concurrent map is for");
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
