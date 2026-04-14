package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CacheConcurrencyDetector}.
 */
class CacheConcurrencyDetectorTest {

    private CacheConcurrencyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CacheConcurrencyDetector();
    }

    @Test
    void testNoIssuesWithConcurrentHashMap() {
        Map<String, String> cache = new ConcurrentHashMap<>();
        detector.registerCache(cache, "concurrent-cache");

        detector.recordGet(cache, "concurrent-cache", "key1");
        detector.recordPut(cache, "concurrent-cache", "key1", "value1");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testDetectsConcurrentReadWriteOnHashMap() {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "unsafe-cache");

        detector.recordGet(cache, "unsafe-cache", "key1");
        detector.recordPut(cache, "unsafe-cache", "key2", "value2");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        // Should detect concurrent read+write on non-concurrent map
        assertTrue(report.hasIssues());
    }

    @Test
    void testDetectsIterationDuringModification() {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "iterating-cache");

        detector.recordPut(cache, "iterating-cache", "key1", "value1");
        detector.recordIteration(cache, "iterating-cache");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
        assertFalse(report.iterationDuringModification.isEmpty());
    }

    @Test
    void testDisabledDetectorReturnsNoIssues() {
        Map<String, String> cache = new HashMap<>();

        detector.disable();
        detector.registerCache(cache, "disabled-cache");
        detector.recordGet(cache, "disabled-cache", "key1");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues());
    }

    @Test
    void testMultipleCachesTracked() {
        Map<String, String> cache1 = new HashMap<>();
        Map<String, String> cache2 = new HashMap<>();

        detector.registerCache(cache1, "cache-1");
        detector.registerCache(cache2, "cache-2");

        detector.recordGet(cache1, "cache-1", "key1");
        detector.recordPut(cache1, "cache-1", "key2", "value2");
        
        detector.recordGet(cache2, "cache-2", "key1");
        detector.recordPut(cache2, "cache-2", "key2", "value2");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
    }

    @Test
    void testReportToStringContainsIssues() {
        Map<String, String> cache = new HashMap<>();

        detector.registerCache(cache, "problematic-cache");
        detector.recordGet(cache, "problematic-cache", "key1");
        detector.recordPut(cache, "problematic-cache", "key2", "value2");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        String reportStr = report.toString();
        assertTrue(reportStr.contains("CACHE CONCURRENCY ISSUES DETECTED"));
    }

    @Test
    void testNullInputsAreIgnored() {
        assertDoesNotThrow(() -> {
            detector.registerCache(null, "test");
            detector.recordGet(null, "test", "key");
            detector.recordPut(null, "test", "key", "value");
            detector.recordIteration(null, "test");
        });
    }

    @Test
    void testAutoRegistrationOnAccess() {
        Map<String, String> cache = new HashMap<>();
        
        // Access without explicit registration
        detector.recordGet(cache, "auto-cache", "key1");
        detector.recordPut(cache, "auto-cache", "key2", "value2");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
    }

    @Test
    void testEnableDisableLifecycle() {
        Map<String, String> cache = new HashMap<>();

        detector.recordGet(cache, "cache", "key1");
        detector.disable();
        
        detector.recordPut(cache, "cache", "key2", "value2");
        
        detector.enable();
        detector.recordGet(cache, "cache", "key3");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        // The put was disabled, so only reads happened
        assertNotNull(report);
    }

    @Test
    void testThreadActivityTracked() {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "threaded-cache");

        detector.recordGet(cache, "threaded-cache", "key1");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty());
        assertTrue(report.threadActivity.get("threaded-cache").contains("reader"));
    }
}
