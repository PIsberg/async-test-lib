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

    /**
     * Runs each body on its own thread, released together, and rethrows whatever died.
     *
     * <p>A cache read and a cache write are only a race when two threads make them. Recording
     * both from the test thread says nothing about concurrency, so the tests that assert a
     * finding have to use two threads to be asserting anything (#497).
     */
    private static void onThreads(Runnable... bodies) throws InterruptedException {
        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(bodies.length);
        java.util.concurrent.atomic.AtomicReference<Throwable> died =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] threads = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    body.run();
                } catch (Throwable t) {
                    died.compareAndSet(null, t);
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        if (died.get() != null) {
            throw new AssertionError("a worker died", died.get());
        }
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
    void testDetectsConcurrentReadWriteOnHashMap() throws InterruptedException {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "unsafe-cache");

        onThreads(
            () -> detector.recordGet(cache, "unsafe-cache", "key1"),
            () -> detector.recordPut(cache, "unsafe-cache", "key2", "value2"));

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        // Should detect concurrent read+write on non-concurrent map
        assertTrue(report.hasIssues());
    }

    @Test
    void aSingleThreadReadingAndWritingItsOwnHashMapIsNotAConcurrencyFinding() {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "confined-cache");

        detector.recordPut(cache, "confined-cache", "key", "value");
        detector.recordGet(cache, "confined-cache", "key");

        assertFalse(detector.analyze().hasIssues(),
            "one thread reading and writing a HashMap it owns is correct code; there is no "
                + "concurrency here at all: " + detector.analyze());
    }

    @Test
    void aHashMapGuardedByItsOwnMonitorIsNotAConcurrencyFinding() throws InterruptedException {
        Map<String, String> cache = new HashMap<>();
        detector.registerCache(cache, "guarded-cache");

        onThreads(
            () -> {
                synchronized (cache) {
                    detector.recordPut(cache, "guarded-cache", "key", "value");
                }
            },
            () -> {
                synchronized (cache) {
                    detector.recordGet(cache, "guarded-cache", "key");
                }
            });

        assertFalse(detector.analyze().hasIssues(),
            "every access is inside synchronized (cache), so one lock covers them all and the "
                + "HashMap is not racing: " + detector.analyze());
    }

    @Test
    void manyThreadsHittingDistinctKeysIsNotACacheStampede() throws InterruptedException {
        Map<String, String> cache = new ConcurrentHashMap<>();
        detector.registerCache(cache, "wide-cache");

        Runnable[] workers = new Runnable[12];
        for (int i = 0; i < workers.length; i++) {
            String key = "key-" + i;
            workers[i] = () -> {
                detector.recordGet(cache, "wide-cache", key);
                detector.recordPut(cache, "wide-cache", key, "value");
            };
        }
        onThreads(workers);

        assertFalse(detector.analyze().hasIssues(),
            "twelve threads each computing their own key is a cache doing its job. The old rule "
                + "counted how many threads were inside the record methods at once, which under "
                + "@AsyncTest measures the runner's barrier rather than the cache: "
                + detector.analyze());
    }

    @Test
    void twoThreadsRecomputingTheSameKeyIsACacheStampede() throws InterruptedException {
        Map<String, String> cache = new ConcurrentHashMap<>();
        detector.registerCache(cache, "hot-key-cache");

        Runnable missAndRecompute = () -> {
            detector.recordGet(cache, "hot-key-cache", "hot");    // miss
            detector.recordPut(cache, "hot-key-cache", "hot", "value");   // recompute
        };
        onThreads(missAndRecompute, missAndRecompute);

        var report = detector.analyze();
        assertFalse(report.cacheStampede.isEmpty(),
            "two threads recomputing the same key is what a cache stampede is: " + report);
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
    void testMultipleCachesTracked() throws InterruptedException {
        Map<String, String> cache1 = new HashMap<>();
        Map<String, String> cache2 = new HashMap<>();

        detector.registerCache(cache1, "cache-1");
        detector.registerCache(cache2, "cache-2");

        onThreads(
            () -> {
                detector.recordGet(cache1, "cache-1", "key1");
                detector.recordGet(cache2, "cache-2", "key1");
            },
            () -> {
                detector.recordPut(cache1, "cache-1", "key2", "value2");
                detector.recordPut(cache2, "cache-2", "key2", "value2");
            });

        CacheConcurrencyDetector.CacheConcurrencyReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues());
    }

    @Test
    void testReportToStringContainsIssues() throws InterruptedException {
        Map<String, String> cache = new HashMap<>();

        detector.registerCache(cache, "problematic-cache");
        onThreads(
            () -> detector.recordGet(cache, "problematic-cache", "key1"),
            () -> detector.recordPut(cache, "problematic-cache", "key2", "value2"));

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
    void testAutoRegistrationOnAccess() throws InterruptedException {
        Map<String, String> cache = new HashMap<>();

        // Access without explicit registration
        onThreads(
            () -> detector.recordGet(cache, "auto-cache", "key1"),
            () -> detector.recordPut(cache, "auto-cache", "key2", "value2"));

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
    void aPlainHashMapUsedAsACacheStillFires() throws InterruptedException {
        Map<String, String> cache = new java.util.HashMap<>();
        detector.registerCache(cache, "plain-cache");
        onThreads(
            () -> detector.recordPut(cache, "plain-cache", "key", "value"),
            () -> detector.recordGet(cache, "plain-cache", "key"));

        assertTrue(detector.analyze().hasIssues(),
                "a HashMap read and written as a cache is the read/write race this detector "
                        + "exists for, and widening the thread-safe set must not cover it");
    }
}
