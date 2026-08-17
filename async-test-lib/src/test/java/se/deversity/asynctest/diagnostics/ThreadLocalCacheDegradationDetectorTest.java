package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadLocalCacheDegradationDetectorTest {

    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT));

    @Test
    void cleanWhenNothingRecorded() {
        var d = new ThreadLocalCacheDegradationDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("THREAD LOCAL CACHE DEGRADATION - clean", d.analyze().toString());
    }

    @Test
    void oneInstancePerVirtualThreadIsDetected() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();

        onVirtualThreads(6, () ->
                d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread()));

        var report = d.analyze();
        assertTrue(report.hasIssues(), () -> "6 threads, 6 formatters, nothing reused:\n" + report);
        var v = report.structuredViolations.get(0);
        assertEquals("ThreadLocalCacheDegradation", v.detector());
        assertEquals(IssueSeverity.MEDIUM, v.severity());
        assertEquals(6, v.attributes().get("virtualInstances"));
        assertEquals(6, v.attributes().get("virtualThreads"));
        assertEquals("SimpleDateFormat", v.attributes().get("valueType"));
    }

    /**
     * The corrected shape: an immutable value shared by every thread. One instance however many
     * threads read it, so the key is still a cache and there is nothing to report.
     */
    @Test
    void aSharedImmutableValueStaysSilent() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        Object shared = new Object();

        onVirtualThreads(8, () -> d.recordCachedValue("SHARED", shared, Thread.currentThread()));

        var report = d.analyze();
        assertFalse(report.hasIssues(), () -> "one instance across every thread is a cache:\n" + report);
    }

    /**
     * The other correct shape: the same {@code ThreadLocal} on a bounded pool. Instances stay at
     * the pool size no matter how many tasks run, which is exactly why this pattern was fine
     * before virtual threads - so platform-only usage is out of scope.
     */
    @Test
    void platformThreadsAreOutOfScope() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofPlatform().start(() ->
                    d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread())));
        }
        for (Thread t : threads) t.join();

        assertFalse(d.analyze().hasIssues(),
                "on a pool the instance count is bounded by the pool, which is the point");
    }

    @Test
    void belowTheThresholdIsSilent() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        onVirtualThreads(3, () -> d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread()));
        assertFalse(d.analyze().hasIssues(), "three instances is not yet evidence of anything");
    }

    @Test
    void fewerInstancesThanThreadsMeansReuse() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector(4);
        Object poolA = new Object();
        Object poolB = new Object();

        // Eight threads sharing two borrowed instances: the helper is pooled, not the thread.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Object borrowed = (i % 2 == 0) ? poolA : poolB;
            threads.add(Thread.ofVirtual().start(() ->
                    d.recordCachedValue("BORROWED", borrowed, Thread.currentThread())));
        }
        for (Thread t : threads) t.join();

        assertFalse(d.analyze().hasIssues(),
                "2 instances across 8 threads is reuse - the fix, not the hazard");
    }

    @Test
    void recordingTheSameValueRepeatedlyChangesNothing() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        onVirtualThreads(6, () -> {
            SimpleDateFormat f = FORMAT.get();
            for (int i = 0; i < 5; i++) {
                d.recordCachedValue("FORMAT", f, Thread.currentThread());   // same object each time
            }
        });

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(6, report.structuredViolations.get(0).attributes().get("virtualInstances"),
                "counting is by identity, so re-reading a value adds nothing");
    }

    @Test
    void aMixedWorkloadNamesThePlatformBaseline() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();

        List<Thread> platform = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            platform.add(Thread.ofPlatform().start(() ->
                    d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread())));
        }
        for (Thread t : platform) t.join();

        onVirtualThreads(6, () -> d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread()));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.toString().contains("which is the bounded behaviour this replaced"),
                report.toString());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("platformThreads"));
    }

    @Test
    void thresholdIsNeverBelowTwo() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector(0);
        onVirtualThreads(1, () -> d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread()));
        assertFalse(d.analyze().hasIssues(), "one thread cannot demonstrate a cache failing to cache");
    }

    @Test
    void nullsAreIgnored() {
        var d = new ThreadLocalCacheDegradationDetector();
        d.recordCachedValue("FORMAT", null, Thread.currentThread());
        d.recordCachedValue("FORMAT", new Object(), null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingNameFallsBackToADefault() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        onVirtualThreads(6, () -> d.recordCachedValue(null, new Object(), Thread.currentThread()));
        assertTrue(d.analyze().violations.get(0).contains("threadLocal"));
    }

    @Test
    void disableStopsRecording() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        d.disable();
        onVirtualThreads(6, () -> d.recordCachedValue("off", new Object(), Thread.currentThread()));
        assertFalse(d.analyze().hasIssues());

        d.enable();
        onVirtualThreads(6, () -> d.recordCachedValue("on", new Object(), Thread.currentThread()));
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() throws Exception {
        var d = new ThreadLocalCacheDegradationDetector();
        onVirtualThreads(6, () -> d.recordCachedValue("FORMAT", FORMAT.get(), Thread.currentThread()));

        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("THREAD LOCAL CACHE DEGRADATION DETECTED"));
        assertTrue(rendered.contains("FORMAT"));
        assertTrue(rendered.contains("Fix:"));
    }

    private static void onVirtualThreads(int count, Runnable body) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            threads.add(Thread.ofVirtual().start(body));
        }
        for (Thread t : threads) t.join();
    }
}
