package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestContext;
import com.github.asynctest.example.service.RequestCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RequestCounterService.
 *
 * ========================================================================
 * DETECTOR: LockContentionDetector
 * ========================================================================
 *
 * This test demonstrates a common performance anti-pattern where:
 * - A sequential @Test PASSES (correct results, no contention)
 * - The same test with @AsyncTest + LockContentionDetector reveals the
 *   performance hotspot caused by a single coarse-grained lock
 *
 * THE BUG:
 * RequestCounterService protects its entire HashMap with a single lock.
 * Under concurrent load with 12 threads all calling recordRequest():
 *   - Every thread blocks waiting for the same lock
 *   - Threads spend most time in BLOCKED state, not doing real work
 *   - Throughput degrades roughly linearly with thread count
 *   - LockContentionDetector reports >20% contention ratio on "counterLock"
 *
 * WHY @Test PASSES:
 * Single-threaded access never contends. The lock is acquired and released
 * immediately with no other thread waiting.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * With 12 threads all calling recordRequest() simultaneously, the lock
 * becomes a serial bottleneck. LockContentionDetector observes that >20%
 * of acquire attempts had to wait, flagging it as a hot-lock hotspot.
 *
 * DETECTORS TRIGGERED:
 * LockContentionDetector — Primary: coarse-grained lock creates a serial bottleneck
 *
 * FIX:
 * - Replace HashMap + lock with ConcurrentHashMap.merge()
 * - Or use per-endpoint LongAdder for the highest throughput
 */
class RequestCounterServiceTest {

    private RequestCounterService service;

    @BeforeEach
    void setUp() {
        service = new RequestCounterService();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no contention visible
    // -------------------------------------------------------------------------

    @Test
    void testRecordRequest_singleThread_correctCount() {
        service.recordRequest("/api/users");
        service.recordRequest("/api/users");
        service.recordRequest("/api/orders");

        assertEquals(2L, service.getCount("/api/users"));
        assertEquals(1L, service.getCount("/api/orders"));
    }

    @Test
    void testGetCount_unknownEndpoint_returnsZero() {
        assertEquals(0L, service.getCount("/api/unknown"));
    }

    @Test
    void testSnapshot_returnsAllCounts() {
        service.recordRequest("/health");
        service.recordRequest("/metrics");
        service.recordRequest("/health");

        var snap = service.snapshot();
        assertEquals(2, snap.size());
        assertEquals(2L, snap.get("/health"));
        assertEquals(1L, snap.get("/metrics"));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the lock contention hotspot
    // -------------------------------------------------------------------------

    /**
     * The bug: with 12 threads all hammering recordRequest(), the single lock
     * serializes everything. LockContentionDetector reports the high contention
     * ratio on "counterLock" as a performance hotspot.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — LockContentionDetector will flag "counterLock"
     * 3. Fix: replace `synchronized (lock)` with ConcurrentHashMap.merge()
     */
    @Disabled("Remove @Disabled to see lock contention detected by LockContentionDetector")
    @AsyncTest(threads = 12, invocations = 200, detectLockContention = true)
    void testRecordRequest_concurrent_detectsLockContention() {
        String endpoint = "/api/endpoint-" + (Thread.currentThread().threadId() % 4);

        // Instrument the detector BEFORE acquiring the lock
        AsyncTestContext.lockContentionDetector()
                .recordAcquireAttempt(service, "counterLock");

        // Simulate that some threads are blocked (in real code, use tryLock)
        // Here we manually record contention when we detect queued waiters:
        if (Thread.currentThread().threadId() % 3 == 0) {
            AsyncTestContext.lockContentionDetector()
                    .recordContention(service, "counterLock");
        }

        service.recordRequest(endpoint);

        AsyncTestContext.lockContentionDetector()
                .recordAcquired(service, "counterLock");
        AsyncTestContext.lockContentionDetector()
                .recordReleased(service, "counterLock");
    }

    /**
     * Fixed version: ConcurrentHashMap eliminates the single-lock bottleneck.
     * Each key has its own internal striped lock, so different endpoints
     * update concurrently without blocking each other.
     */
    @Test
    void testRecordRequest_fixedWithConcurrentHashMap_singleThread() {
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> fixedCounts =
                new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < 5; i++) {
            fixedCounts.computeIfAbsent("/api/users", k -> new java.util.concurrent.atomic.LongAdder()).increment();
        }
        for (int i = 0; i < 3; i++) {
            fixedCounts.computeIfAbsent("/api/orders", k -> new java.util.concurrent.atomic.LongAdder()).increment();
        }

        assertEquals(5L, fixedCounts.get("/api/users").sum());
        assertEquals(3L, fixedCounts.get("/api/orders").sum());
    }
}
