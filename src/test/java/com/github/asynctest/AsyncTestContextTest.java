package com.github.asynctest;

import com.github.asynctest.diagnostics.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Phase 2 @AsyncTest flags are no longer dead code:
 * - Detectors are initialised when their flag is set
 * - AsyncTestContext.get() / static accessors work inside @AsyncTest methods
 * - Attempting to access a disabled detector throws a clear error
 * - AsyncTestConfig.from() mirrors the annotation correctly
 */
class AsyncTestContextTest {

    // ---- AsyncTestConfig.from() mirrors annotation ----

    @AsyncTest(
        threads = 3, invocations = 2,
        useVirtualThreads = false,
        detectFalseSharing = true,
        detectWakeupIssues = true,
        validateConstructorSafety = true,
        detectABAProblem = true,
        validateLockOrder = true,
        monitorSynchronizers = true,
        monitorThreadPool = true,
        detectMemoryOrderingViolations = true,
        monitorAsyncPipeline = true,
        monitorReadWriteLockFairness = true,
        timeoutMs = 5_000
    )
    void phase2ContextIsActiveInsideTest() {
        // All Phase 2 detectors must be accessible without throwing
        assertNotNull(AsyncTestContext.get(), "context must be non-null inside @AsyncTest");

        assertNotNull(AsyncTestContext.falseSharingDetector());
        assertNotNull(AsyncTestContext.wakeupDetector());
        assertNotNull(AsyncTestContext.constructorSafetyValidator());
        assertNotNull(AsyncTestContext.abaProblemDetector());
        assertNotNull(AsyncTestContext.lockOrderValidator());
        assertNotNull(AsyncTestContext.synchronizerMonitor());
        assertNotNull(AsyncTestContext.threadPoolMonitor());
        assertNotNull(AsyncTestContext.memoryOrderingMonitor());
        assertNotNull(AsyncTestContext.pipelineMonitor());
        assertNotNull(AsyncTestContext.readWriteLockMonitor());
    }

    @Test
    void contextIsNullOutsideTest() {
        assertNull(AsyncTestContext.get(),
            "AsyncTestContext must be null outside @AsyncTest execution");
    }

    @Test
    void accessingDisabledDetectorThrowsIllegalState() {
        // Build a config with detectFalseSharing = false (default)
        AsyncTestConfig cfg = AsyncTestConfig.builder().build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AsyncTestContext.install(ctx);
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                AsyncTestContext::falseSharingDetector);
            assertTrue(ex.getMessage().contains("detectFalseSharing"),
                "Error message must name the disabled flag");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    void accessingContextOutsideTestThrowsIllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            AsyncTestContext::falseSharingDetector);
        assertTrue(ex.getMessage().contains("AsyncTestContext is not active"));
    }

    // ---- Detectors are shared across threads and record events ----

    @AsyncTest(threads = 3, invocations = 2, useVirtualThreads = false,
               detectFalseSharing = true, timeoutMs = 5_000)
    void falseSharingDetectorIsSharedAcrossThreads() {
        // All threads use the same detector instance — events accumulate
        FalseSharingDetector detector = AsyncTestContext.falseSharingDetector();
        detector.recordFieldAccess(this, "fieldA", int.class);
        detector.recordFieldAccess(this, "fieldB", int.class);
        // No assertion here — we just verify it doesn't throw
    }

    @AsyncTest(threads = 2, invocations = 2, useVirtualThreads = false,
               detectABAProblem = true, timeoutMs = 5_000)
    void abaDetectorRecordsEvents() {
        ABAProblemDetector detector = AsyncTestContext.abaProblemDetector();
        detector.recordValueChange("x", "A", "B");
        detector.recordValueChange("x", "B", "A");
        // Report will have cycles — just verify detector is wired
    }

    // ---- AsyncTestConfig builder defaults match @AsyncTest defaults ----

    @Test
    void configBuilderDefaultsMatchAnnotationDefaults() {
        AsyncTestConfig defaults = AsyncTestConfig.builder().build();
        assertEquals(10,     defaults.threads);
        assertEquals(100,    defaults.invocations);
        assertTrue(defaults.useVirtualThreads);
        assertEquals(5_000L, defaults.timeoutMs);
        assertEquals("OFF",  defaults.virtualThreadStressMode);
        assertTrue(defaults.detectDeadlocks);
        assertFalse(defaults.detectVisibility);
        assertFalse(defaults.detectFalseSharing);
        assertFalse(defaults.detectWakeupIssues);
        assertFalse(defaults.validateConstructorSafety);
        assertFalse(defaults.detectABAProblem);
        assertFalse(defaults.validateLockOrder);
        assertFalse(defaults.monitorSynchronizers);
        assertFalse(defaults.monitorThreadPool);
        assertFalse(defaults.detectMemoryOrderingViolations);
        assertFalse(defaults.monitorAsyncPipeline);
        assertFalse(defaults.monitorReadWriteLockFairness);
    }

    // ---- Same detector instance is reused across invocation rounds ----

    private final AtomicReference<ABAProblemDetector> capturedDetector = new AtomicReference<>();
    private final AtomicBoolean sameInstanceAcrossRounds = new AtomicBoolean(true);

    @AsyncTest(threads = 2, invocations = 3, useVirtualThreads = false,
               detectABAProblem = true, timeoutMs = 5_000)
    void sameDetectorInstanceAcrossInvocationRounds() {
        ABAProblemDetector current = AsyncTestContext.abaProblemDetector();
        if (!capturedDetector.compareAndSet(null, current)) {
            // Subsequent rounds/threads must see the same instance
            if (capturedDetector.get() != current) {
                sameInstanceAcrossRounds.set(false);
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void verifySameInstance() {
        // Only check after the @AsyncTest above runs
        if (capturedDetector.get() != null) {
            assertTrue(sameInstanceAcrossRounds.get(),
                "Phase 2 detectors must be the same instance across all threads and invocation rounds");
        }
    }

    // ---- analyzeAll() returns empty list when no detectors are enabled ----

    @Test
    void analyzeAllReturnsEmptyWhenNoDetectorsEnabled() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build(); // everything disabled except deadlocks (Phase 1)
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        List<String> reports = ctx.analyzeAll();
        assertNotNull(reports, "analyzeAll must never return null");
        assertTrue(reports.isEmpty(),
            "No Phase 2 detectors enabled => analyzeAll must return empty list");
    }

    // ---- analyzeAll() collects reports from enabled detectors ----

    @Test
    void analyzeAllCollectsReportsFromEnabledDetectors() {
        // Enable LockLeak detector and simulate an unreleased lock so the report has issues.
        // LockLeakDetector.hasIssues() is true when any lock was acquired but never released.
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectLockLeaks(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);

        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        ctx.lockLeakDetector.registerLock(lock, "leaky");
        lock.lock();
        ctx.lockLeakDetector.recordLockAcquired(lock, "leaky");
        // Intentionally NOT releasing — simulates a lock leak

        List<String> reports = ctx.analyzeAll();
        assertFalse(reports.isEmpty(),
            "LockLeak detector with unreleased lock must produce at least one report");
    }

    // ---- install / uninstall isolates context per thread ----

    @Test
    void installAndUninstallIsolateContextPerThread() throws InterruptedException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build();
        AsyncTestContext ctxA = new AsyncTestContext(cfg);
        AsyncTestContext ctxB = new AsyncTestContext(cfg);

        AtomicReference<AsyncTestContext> seenInThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Main thread installs ctxA
        AsyncTestContext.install(ctxA);
        try {
            // Spawn a thread that installs ctxB and records what it sees
            Thread t = new Thread(() -> {
                AsyncTestContext.install(ctxB);
                seenInThread.set(AsyncTestContext.get());
                AsyncTestContext.uninstall();
                latch.countDown();
            });
            t.start();
            latch.await();

            // Main thread must still see ctxA (ThreadLocal isolation)
            assertSame(ctxA, AsyncTestContext.get(),
                "Main thread context must not be affected by other thread's install");
            assertSame(ctxB, seenInThread.get(),
                "Spawned thread must see its own installed context");
        } finally {
            AsyncTestContext.uninstall();
        }

        assertNull(AsyncTestContext.get(), "After uninstall, context must be null");
    }

    @Test
    void uninstallLeavesNoThreadLocalTrace() throws InterruptedException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AtomicBoolean leakDetected = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            AsyncTestContext.install(ctx);
            AsyncTestContext.uninstall();
            // After uninstall the ThreadLocal must be absent
            if (AsyncTestContext.get() != null) leakDetected.set(true);
            latch.countDown();
        });
        t.start();
        latch.await();

        assertFalse(leakDetected.get(), "ThreadLocal must be cleaned up after uninstall");
    }
}
