package se.deversity.asynctest.diagnostics;

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

    /**
     * A cache that keeps the {@code ConcurrentMap} contract is not a non-thread-safe cache.
     *
     * <p>The check used to be {@code instanceof ConcurrentHashMap}, which is one implementation
     * rather than the contract, so every other correct concurrent map was reported. This stands
     * in for Caffeine's {@code asMap()} view, where the corpus eval's recording lane caught it:
     * a {@code ConcurrentSkipListMap} is in the JDK, implements the same interface and is not a
     * {@code ConcurrentHashMap}.
     */
    @Test
    void aConcurrentMapThatIsNotAConcurrentHashMapIsStillThreadSafe() {
        Map<String, String> cache = new java.util.concurrent.ConcurrentSkipListMap<>();
        detector.registerCache(cache, "skiplist-cache");
        detector.recordPut(cache, "skiplist-cache", "key", "value");
        detector.recordGet(cache, "skiplist-cache", "key");

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertFalse(report.hasIssues(),
                "a ConcurrentMap keeps its contract whatever its concrete class; reporting one "
                        + "as a non-thread-safe cache is noise on correct code. Got: " + report);
    }

    /** The legacy synchronized collections keep the same promise by taking their own monitor. */
    @Test
    void aSynchronizedMapWrapperIsThreadSafe() {
        Map<String, String> cache =
                java.util.Collections.synchronizedMap(new java.util.HashMap<>());
        detector.registerCache(cache, "wrapped-cache");
        detector.recordPut(cache, "wrapped-cache", "key", "value");
        detector.recordGet(cache, "wrapped-cache", "key");

        assertFalse(detector.analyze().hasIssues(),
                "every method of a synchronized wrapper takes the instance's own monitor, which "
                        + "is the same promise ConcurrentMap makes by another route");
    }

    /**
     * The twin: the fix must not silence the case the detector exists for.
     *
     * <p>A plain {@code HashMap} read and written from a cache position is exactly the defect,
     * and it has to keep firing after the widening above - otherwise "no findings" would mean
     * the detector stopped looking rather than that the code is correct.
     */
    @Test
    void aPlainHashMapUsedAsACacheStillFires() {
        Map<String, String> cache = new java.util.HashMap<>();
        detector.registerCache(cache, "plain-cache");
        detector.recordPut(cache, "plain-cache", "key", "value");
        detector.recordGet(cache, "plain-cache", "key");

        assertTrue(detector.analyze().hasIssues(),
                "a HashMap read and written as a cache is the read/write race this detector "
                        + "exists for, and widening the thread-safe set must not cover it");
    }
}
