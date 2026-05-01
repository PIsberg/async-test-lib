package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MutableMapKeyDetectorTest {

    static class MutableKey {
        String value;
        MutableKey(String v) { this.value = v; }

        @Override public int hashCode()            { return value.hashCode(); }
        @Override public boolean equals(Object o)  {
            return o instanceof MutableKey mk && mk.value.equals(this.value);
        }
    }

    @Test
    void testNoIssuesForUnmutatedKey() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("stable");
        map.put(key, "v");
        detector.recordKeyInserted(map, key, "my-map");
        // no mutation recorded

        MutableMapKeyDetector.MutableMapKeyReport report = detector.analyze();
        assertFalse(report.hasIssues(), "Unmutated key should not report issues");
    }

    @Test
    void testDetectsMutation() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("before");
        map.put(key, "v");
        detector.recordKeyInserted(map, key, "test-map");

        key.value = "after";
        detector.recordKeyMutation(key, "value", "before", "after");

        MutableMapKeyDetector.MutableMapKeyReport report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect mutation after insertion");
        assertFalse(report.mutatedKeys.isEmpty());
        assertTrue(report.mutatedKeys.get(0).contains("test-map"));
    }

    @Test
    void testHashCodeChangeIsRecordedInDetails() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("alpha");
        int before = key.hashCode();
        map.put(key, "v");
        detector.recordKeyInserted(map, key, "hash-map");

        key.value = "beta";
        detector.recordKeyMutation(key, "value", "alpha", "beta");

        MutableMapKeyDetector.MutableMapKeyReport report = detector.analyze();
        assertTrue(report.hasIssues());
        // Detail should mention hashCode change (alpha ≠ beta)
        boolean hashMention = report.mutationDetails.stream()
                .anyMatch(d -> d.contains("hashCode changed") || d.contains("hash"));
        assertTrue(hashMention, "Should mention hashCode change in details");
    }

    @Test
    void testMultipleMutationsCountedCorrectly() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("v0");
        map.put(key, "x");
        detector.recordKeyInserted(map, key, "m");

        key.value = "v1";
        detector.recordKeyMutation(key, "value", "v0", "v1");
        key.value = "v2";
        detector.recordKeyMutation(key, "value", "v1", "v2");

        MutableMapKeyDetector.MutableMapKeyReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.mutatedKeys.get(0).contains("2 time(s)"));
    }

    @Test
    void testNullSafety() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        assertDoesNotThrow(() -> {
            detector.recordKeyInserted(null, new Object(), "m");
            detector.recordKeyInserted(new HashMap<>(), null, "m");
            detector.recordKeyMutation(null, "f", "a", "b");
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testAutoNameFromIdentityHash() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("x");
        map.put(key, "v");
        detector.recordKeyInserted(map, key, null); // no name
        key.value = "y";
        detector.recordKeyMutation(key, "value", "x", "y");

        MutableMapKeyDetector.MutableMapKeyReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.mutatedKeys.get(0).contains("map@"));
    }

    @Test
    void testMutationOfUnregisteredKeyIsIgnored() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        MutableKey key = new MutableKey("unregistered");
        // never called recordKeyInserted
        assertDoesNotThrow(() -> detector.recordKeyMutation(key, "value", "a", "b"));
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        MutableMapKeyDetector detector = new MutableMapKeyDetector();
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("hint");
        map.put(key, "v");
        detector.recordKeyInserted(map, key, "m");
        key.value = "mutated";
        detector.recordKeyMutation(key, "value", "hint", "mutated");

        String str = detector.analyze().toString();
        assertTrue(str.contains("MUTABLE MAP KEY ISSUES DETECTED"));
        assertTrue(str.contains("Fix"));
        assertTrue(str.contains("immutable"));
    }
}
