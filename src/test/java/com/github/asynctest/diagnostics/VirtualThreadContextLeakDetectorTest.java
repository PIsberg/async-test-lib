package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VirtualThreadContextLeakDetector}.
 */
class VirtualThreadContextLeakDetectorTest {

    private VirtualThreadContextLeakDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VirtualThreadContextLeakDetector();
    }

    // ---- No issues ----

    @Test
    void noIssues_whenThreadLocalSetAndRemoved() {
        Thread thread = Thread.currentThread();
        detector.recordThreadLocalSet("REQUEST_ID", thread);
        detector.recordThreadLocalRemoved("REQUEST_ID", thread);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
    }

    @Test
    void noIssues_whenMultipleKeysAllRemoved() {
        Thread thread = Thread.currentThread();
        detector.recordThreadLocalSet("KEY_A", thread);
        detector.recordThreadLocalSet("KEY_B", thread);
        detector.recordThreadLocalSet("KEY_C", thread);
        detector.recordThreadLocalRemoved("KEY_A", thread);
        detector.recordThreadLocalRemoved("KEY_B", thread);
        detector.recordThreadLocalRemoved("KEY_C", thread);

        var report = detector.analyze();
        assertFalse(report.hasIssues());
        assertEquals(0, report.getLeaks().size());
    }

    // ---- Leak detection (using platform thread — still tracked) ----

    @Test
    void noLeakReport_forPlatformThreadThatNeverRemoves_becauseLeakOnlyTracksVirtualThreads() {
        // Platform threads: we record but leaks are only flagged for virtual threads
        Thread platformThread = Thread.currentThread(); // platform thread
        assertFalse(VirtualThreadContextLeakDetector.isVirtualThread(platformThread));

        detector.recordThreadLocalSet("REQUEST_ID", platformThread);
        // never removed

        var report = detector.analyze();
        // Platform thread leaks are NOT flagged (they don't re-pool in the same way)
        assertTrue(report.getLeaks().isEmpty(),
            "Platform thread ThreadLocal not removed should not be reported as virtual-thread leak");
    }

    // ---- InheritableThreadLocal misuse ----

    @Test
    void detectsInheritableThreadLocalInVirtualThread_whenOnVirtualThread() throws Exception {
        // We need an actual virtual thread for this
        Thread[] capture = new Thread[1];
        Thread vt = Thread.ofVirtual().start(() -> {
            capture[0] = Thread.currentThread();
            detector.recordThreadLocalSet("CONTEXT", Thread.currentThread(), true /* isInheritable */);
            detector.recordThreadLocalRemoved("CONTEXT", Thread.currentThread());
        });
        vt.join();

        var report = detector.analyze();
        assertFalse(report.getInheritableInVirtualIssues().isEmpty(),
            "Should warn about InheritableThreadLocal used in virtual thread");
        String issue = report.getInheritableInVirtualIssues().get(0);
        assertTrue(issue.contains("InheritableThreadLocal"), issue);
        assertTrue(issue.contains("ScopedValue"), issue);
    }

    @Test
    void noInheritableWarning_whenRegularThreadLocalInVirtualThread() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            detector.recordThreadLocalSet("REQUEST_ID", Thread.currentThread(), false /* regular */);
            detector.recordThreadLocalRemoved("REQUEST_ID", Thread.currentThread());
        });
        vt.join();

        var report = detector.analyze();
        assertTrue(report.getInheritableInVirtualIssues().isEmpty(),
            "Regular ThreadLocal in virtual thread should not generate InheritableThreadLocal warning");
    }

    // ---- Virtual thread leak ----

    @Test
    void detectsLeak_whenVirtualThreadSetsButNeverRemoves() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            detector.recordThreadLocalSet("REQUEST_ID", Thread.currentThread());
            // No remove
        });
        vt.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Should detect virtual thread context leak");
        assertFalse(report.getLeaks().isEmpty());
        String leak = report.getLeaks().get(0);
        assertTrue(leak.contains("REQUEST_ID"), leak);
        assertTrue(leak.contains("never removed"), leak);
    }

    @Test
    void noLeak_whenVirtualThreadProperlyRemoves() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            detector.recordThreadLocalSet("REQUEST_ID", Thread.currentThread());
            detector.recordThreadLocalRemoved("REQUEST_ID", Thread.currentThread());
        });
        vt.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no leak when properly removed: " + report);
        assertTrue(report.getLeaks().isEmpty());
    }

    // ---- Stats ----

    @Test
    void tracksSetAndRemoveCounts() {
        Thread thread = Thread.currentThread();
        detector.recordThreadLocalSet("A", thread);
        detector.recordThreadLocalSet("B", thread);
        detector.recordThreadLocalRemoved("A", thread);

        var report = detector.analyze();
        assertEquals(2, report.getTotalSets());
        assertEquals(1, report.getTotalRemoves());
    }

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordThreadLocalSet(null, Thread.currentThread());
            detector.recordThreadLocalSet("key", null);
            detector.recordThreadLocalRemoved(null, Thread.currentThread());
            detector.recordThreadLocalRemoved("key", null);
        });
    }

    // ---- toString ----

    @Test
    void toString_containsLearningContent_whenIssuesFound() throws Exception {
        Thread vt = Thread.ofVirtual().start(() ->
            detector.recordThreadLocalSet("LEAK_KEY", Thread.currentThread())
        );
        vt.join();

        var report = detector.analyze();
        String str = report.toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("ScopedValue"), str);
    }

    @Test
    void toString_isClean_whenNoIssues() {
        var report = detector.analyze();
        assertTrue(report.toString().contains("No ThreadLocal context leaks"));
    }
}
