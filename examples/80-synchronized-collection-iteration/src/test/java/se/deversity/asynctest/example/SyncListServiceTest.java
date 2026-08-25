package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SyncListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SyncListService.
 *
 * ========================================================================
 * DETECTOR: SynchronizedCollectionIterationDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (no concurrent mutations during iteration)
 * - The same scenario with @AsyncTest FAILS (CME or corrupted snapshot)
 *
 * THE BUG:
 * SyncListService.snapshot() iterates a Collections.synchronizedList via a
 * for-each loop without holding the wrapper's intrinsic lock. A concurrent
 * add() between two iterator.next() calls throws ConcurrentModificationException.
 *
 * WHY @Test PASSES:
 * With one thread there are no concurrent modifications — the iteration
 * completes without interference and the snapshot is always correct.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * SynchronizedCollectionIterationDetector.recordWrapperCreated() registers the
 * list. recordIterationStarted() checks whether the calling thread holds the
 * wrapper's lock at the moment iteration begins. Without it, the violation is
 * recorded and flagged in the analysis report.
 *
 * DETECTORS TRIGGERED:
 *   SynchronizedCollectionIterationDetector — primary: detects lock-free iteration
 *
 * FIX: synchronize on the wrapper list before iterating.
 */
class SyncListServiceTest {

    private SyncListService service;

    @BeforeEach
    void setUp() {
        service = new SyncListService();
        service.add("alpha");
        service.add("beta");
        service.add("gamma");
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, always correct
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_snapshot_returnsAllItems() {
        var snapshot = service.snapshot();
        assertEquals(3, snapshot.size());
        assertTrue(snapshot.contains("alpha"));
    }

    @Test
    void test_singleThread_add_increaseSize() {
        service.add("delta");
        assertEquals(4, service.size());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unsafe iteration of synchronized list
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unsafe iteration detected by SynchronizedCollectionIterationDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSynchronizedCollectionIteration = true, failOn = FailOn.LOW)
    void test_concurrent_detectsUnsafeIteration() {
        var detector = AsyncTestContext.get().synchronizedCollectionIterationMonitor();
        var items = service.getItems();

        // Register the wrapper once; detector deduplicates by identity.
        detector.recordWrapperCreated(items, "sync-items");

        // Record that iteration is starting — the thread does NOT hold the lock.
        // holdingLock = false triggers the violation in the detector.
        boolean holdingLock = Thread.holdsLock(items);
        detector.recordIterationStarted(items, Thread.currentThread(), holdingLock);

        // Perform the buggy unsynchronized iteration while other threads add().
        service.add("item-" + Thread.currentThread().getName());
        service.snapshot();
    }
}
