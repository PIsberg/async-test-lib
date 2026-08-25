package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ReadHeavyCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ReadHeavyCache.
 *
 * ========================================================================
 * DETECTOR: ReadWriteLockMonitor (via AsyncTestContext.readWriteLockMonitor())
 * ========================================================================
 *
 * This test demonstrates how a non-fair ReentrantReadWriteLock allows
 * continuously arriving readers to indefinitely delay waiting writers.
 *
 * THE BUG:
 * ReadHeavyCache uses a non-fair ReentrantReadWriteLock(false). In a
 * production system with many concurrent reads and infrequent cache updates:
 *   - Thread A calls update("price:SKU-99", "29.99") and queues for the write lock
 *   - While Thread A waits, Thread B, C, D, ... acquire the read lock immediately
 *     (non-fair policy: new readers are admitted as long as no writer HOLDS the lock)
 *   - Thread A's write lock request waits as new readers keep arriving
 *   - The cache entry for "price:SKU-99" stays stale indefinitely under load
 *
 * WHY @Test PASSES:
 * Sequential tests never produce the continuous reader stream that starves the writer.
 * Writes always complete quickly with no competition.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ReadWriteLockMonitor tracks read-to-write ratios and write wait times.
 * When the read count far exceeds the write count and write wait times are
 * elevated, it reports the writer-starvation pattern.
 *
 * DETECTORS TRIGGERED:
 * ReadWriteLockMonitor — accessed via AsyncTestContext.readWriteLockMonitor()
 *                        (wired through DetectorRegistry, enabled via
 *                         monitorReadWriteLockFairness = true in @AsyncTest).
 *
 * FIX:
 * Construct the lock with fair=true: new ReentrantReadWriteLock(true).
 * This queues both readers and writers in arrival order, ensuring writers
 * are eventually served even under sustained read load.
 */
class ReadHeavyCacheTest {

    private ReadHeavyCache cache;

    @BeforeEach
    void setUp() {
        cache = new ReadHeavyCache();
        cache.update("product:P001", "Laptop Pro 15");
        cache.update("product:P002", "Wireless Mouse");
        cache.update("price:P001",   "1299.00");
        cache.update("price:P002",   "24.99");
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — cache works correctly in single-threaded use
    // -------------------------------------------------------------------------

    @Test
    void testGet_existingKey_returnsValue() {
        assertEquals("Laptop Pro 15", cache.get("product:P001"));
        assertEquals("24.99", cache.get("price:P002"));
    }

    @Test
    void testGet_missingKey_returnsNull() {
        assertNull(cache.get("product:UNKNOWN"));
    }

    @Test
    void testUpdate_existingKey_overwritesValue() {
        cache.update("price:P001", "1199.00");
        assertEquals("1199.00", cache.get("price:P001"));
    }

    @Test
    void testInvalidate_removesEntry() {
        cache.invalidate("product:P002");
        assertNull(cache.get("product:P002"));
        assertEquals(3, cache.size());
    }

    @Test
    void testSize_afterSetup_returnsCorrectCount() {
        assertEquals(4, cache.size());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes writer starvation via ReadWriteLockMonitor
    // -------------------------------------------------------------------------

    /**
     * The bug: under concurrent load, most threads read from the cache while
     * one thread occasionally tries to update it. With a non-fair lock, the
     * reader stream allows new readers to jump ahead of the waiting writer,
     * delaying cache updates indefinitely.
     *
     * ReadWriteLockMonitor reports:
     * - A read-dominated lock (many more reads than writes)
     * - Elevated write wait times (writer starvation events)
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — ReadWriteLockMonitor will flag the starvation pattern
     * 3. Fix: change ReentrantReadWriteLock(false) to ReentrantReadWriteLock(true)
     */
    @Disabled("Remove @Disabled to see writer starvation detected by ReadWriteLockMonitor")
    @AsyncTest(threads = 8, invocations = 100, monitorReadWriteLockFairness = true, failOn = FailOn.LOW)
    void testCache_concurrent_detectsWriterStarvation() {
        ReentrantReadWriteLock rwLock = (ReentrantReadWriteLock) cache.getLock();

        // Register the lock with the monitor
        AsyncTestContext.readWriteLockMonitor().registerLock(rwLock, "catalog-cache-lock");

        long threadId = Thread.currentThread().threadId();

        if (threadId % 8 != 0) {
            // 7 out of 8 threads: read operations (read-heavy workload)
            long readStart = System.currentTimeMillis();
            String value = cache.get("product:P001");
            long readWait = System.currentTimeMillis() - readStart;

            AsyncTestContext.readWriteLockMonitor()
                    .recordReadLockAcquired(rwLock, readWait);
            assertNotNull(value);
            AsyncTestContext.readWriteLockMonitor()
                    .recordReadLockReleased(rwLock);
        } else {
            // 1 in 8 threads: write operation (cache invalidation / price update)
            long writeStart = System.currentTimeMillis();
            // Simulate a delay before the writer gets the lock (starvation window)
            long writeWait = System.currentTimeMillis() - writeStart + 120; // simulate 120ms wait

            AsyncTestContext.readWriteLockMonitor()
                    .recordWriteLockAcquired(rwLock, writeWait);
            cache.update("price:P001", String.valueOf(1299.00 - threadId));
            AsyncTestContext.readWriteLockMonitor()
                    .recordWriteLockReleased(rwLock);
        }
    }

    /**
     * Demonstrates a fair lock configuration that prevents writer starvation.
     * With fair=true, arriving readers must queue behind any waiting writers.
     */
    @Test
    void testFairLock_writerNotStarved() throws InterruptedException {
        ReentrantReadWriteLock fairLock = new ReentrantReadWriteLock(true);

        var monitor = new se.deversity.asynctest.diagnostics.ReadWriteLockMonitor();
        monitor.registerLock(fairLock, "fair-catalog-lock");

        // Simulate balanced reads and writes with no starvation
        for (int i = 0; i < 5; i++) {
            fairLock.readLock().lock();
            try {
                monitor.recordReadLockAcquired(fairLock, 1);
                // Read operation
            } finally {
                fairLock.readLock().unlock();
                monitor.recordReadLockReleased(fairLock);
            }
        }

        // Write operation with minimal wait
        fairLock.writeLock().lock();
        try {
            monitor.recordWriteLockAcquired(fairLock, 2); // 2ms wait — fair, not starved
            // Update operation
        } finally {
            fairLock.writeLock().unlock();
            monitor.recordWriteLockReleased(fairLock);
        }

        var report = monitor.analyzeFairness();
        // 5 reads / 1 write = 5:1 ratio — below the 10:1 starvation threshold
        assertFalse(report.starvedWriters.contains("fair-catalog-lock"),
            "Fair lock should not report writer starvation.\n" + report);
    }
}
