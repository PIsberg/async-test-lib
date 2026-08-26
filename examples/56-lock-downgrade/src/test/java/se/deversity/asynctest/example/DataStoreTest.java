package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.LockDowngradeDetector;
import se.deversity.asynctest.example.service.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DataStore, and for what LockDowngradeDetector does and does not report.
 *
 * ========================================================================
 * DETECTOR: LockDowngradeDetector
 * ========================================================================
 *
 * THE EXAMPLE'S BUG:
 * DataStore.updateAndRead() releases the write lock before acquiring the read
 * lock. In that gap another thread can write the same key, so the caller reads
 * back a value it did not write. updateAndReadFixed() takes the read lock while
 * still holding the write lock, which is the correct downgrade.
 *
 * WHAT THE DETECTOR REPORTS:
 * Two things. The unsafe downgrade above, and the read-to-write upgrade that
 * ReentrantReadWriteLock cannot grant.
 *
 * The downgrade finding is evidence-gated, and that is worth understanding before
 * reading the demonstration. "Released the write lock, then took the read lock" is
 * also the shape of perfectly correct code that writes one thing and later reads
 * another, and nothing in the recorded events distinguishes the two. So the shape
 * alone is not reported. It is reported when another thread was seen taking the
 * write lock inside the gap, which is a fact about the run and the exact reason
 * the downgrade is unsafe. Under @AsyncTest, with eight threads colliding on the
 * same lock, that is what happens: a thread that has just released the write lock
 * blocks in readLock().lock() behind whichever thread took it next.
 *
 * This example carried no demonstration for a while, because the detector reported
 * upgrades only and the demonstration it used to have promised a detection that
 * could not happen. That was issue #346; the gap in the detector was #355, now
 * closed, and the demonstration is back.
 *
 * See also: examples/111-lock-upgrade-deadlock, which demonstrates the upgrade.
 */
class DataStoreTest {

    private DataStore store;

    @BeforeEach
    void setUp() {
        store = new DataStore();
    }

    // -------------------------------------------------------------------------
    // Part 1: the store itself
    // -------------------------------------------------------------------------

    @Test
    void test_singleThread_updateAndRead_works() {
        assertEquals("value1", store.updateAndRead("key1", "value1"));
    }

    @Test
    void test_singleThread_read_returnsNull_whenMissing() {
        assertNull(store.read("missing"));
    }

    @Test
    void test_updateAndReadFixed_returnsTheValueItWrote() {
        assertEquals("value2", store.updateAndReadFixed("key2", "value2"));
    }

    /**
     * The upgrade never succeeds. Written with lock() instead of tryLock() this call would
     * never return at all, which is the deadlock the detector is named for reporting.
     */
    @Test
    void test_readThenUpdate_neverGetsTheWriteLock() throws Exception {
        assertFalse(store.readThenUpdate("key3", "value3"),
                "a thread holding the read lock cannot be granted the write lock");
    }

    // -------------------------------------------------------------------------
    // Part 2: what LockDowngradeDetector reports
    // -------------------------------------------------------------------------

    /**
     * The one thing it reports: the read-to-write upgrade.
     */
    @Test
    void testLockDowngradeDetector_readThenWriteOnOneThread_reports() throws Exception {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        wire(detector);

        store.readThenUpdate("key", "value");

        assertTrue(detector.analyze().hasIssues(),
                "acquiring write while holding read is the upgrade this detector reports");
    }

    /**
     * The correct downgrade is not a finding, which is right.
     */
    @Test
    void testLockDowngradeDetector_correctDowngrade_isSilent() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        wire(detector);

        store.updateAndReadFixed("key", "value");

        assertFalse(detector.analyze().hasIssues(),
                "taking the read lock while still holding the write lock is the correct downgrade");
    }

    /**
     * The incorrect downgrade, on one thread, with nobody in the gap. Not a finding, and that is
     * the deliberate part: on a single thread the recorded sequence is indistinguishable from a
     * write followed by an unrelated read, which is correct code. See issue #355.
     */
    @Test
    void testLockDowngradeDetector_incorrectDowngradeAlone_isSilent() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        wire(detector);

        store.updateAndRead("key", "value");

        assertFalse(detector.analyze().hasIssues(),
                "one thread, nobody in the gap: nothing was observed being lost, and the shape "
                        + "alone is also what correct code produces");
    }

    /**
     * The same call with a writer actually getting into the gap. Driven by hand rather than by
     * contention, so this runs on every build without depending on the scheduler.
     */
    @Test
    void testLockDowngradeDetector_writerInsideTheGap_reports() throws Exception {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        wire(detector);

        Thread interloper = new Thread(() -> {
            store.dataLock.writeLock().lock();
            try {
                detector.recordWriteLockAcquired(store.dataLock, "dataLock");
            } finally {
                detector.recordWriteLockReleased(store.dataLock, "dataLock");
                store.dataLock.writeLock().unlock();
            }
        }, "interloper");

        store.dataLock.writeLock().lock();
        detector.recordWriteLockAcquired(store.dataLock, "dataLock");
        store.dataLock.writeLock().unlock();
        detector.recordWriteLockReleased(store.dataLock, "dataLock");   // the gap opens
        interloper.start();
        interloper.join(5_000);                                         // and is used
        store.dataLock.readLock().lock();
        detector.recordReadLockAcquired(store.dataLock, "dataLock");    // the gap closes
        store.dataLock.readLock().unlock();
        detector.recordReadLockReleased(store.dataLock, "dataLock");

        LockDowngradeDetector.LockDowngradeReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "another thread held the write lock between the release and the read, so what "
                        + "comes back need not be what was written. " + report);
        assertTrue(report.toString().contains("unsafe downgrade"), report.toString());
    }

    // -------------------------------------------------------------------------
    // Part 3: the demonstration
    // -------------------------------------------------------------------------

    /**
     * The bug under contention.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test - it fails with
     *      Lock 'dataLock': N unsafe downgrade(s) observed - a thread released the write
     *      lock and then acquired the read lock, and another thread took the write lock
     *      in between, so the read need not return what the writer wrote
     * 3. Fix: call updateAndReadFixed(), which takes the read lock while still holding
     *    the write lock
     *
     * The detector has to be the one the run owns, from AsyncTestContext. A locally
     * constructed LockDowngradeDetector is never read by the library, so failOn would
     * have nothing to gate on. See issue #346.
     */
    @Disabled("Remove @Disabled to see the unsafe downgrade detected by LockDowngradeDetector")
    @AsyncTest(threads = 8, invocations = 20, detectAll = false,
            detectLockDowngrade = true, failOn = FailOn.LOW)
    void testUpdateAndRead_concurrent_detectsUnsafeDowngrade() {
        LockDowngradeDetector detector = AsyncTestContext.lockDowngradeMonitor();
        wire(detector);

        // The return value is deliberately not asserted: reading back somebody else's value is
        // the bug, and asserting on it would make this demonstration fail for a reason other
        // than the detector's finding.
        store.updateAndRead("shared-key", "value-" + Thread.currentThread().threadId());
    }

    private void wire(LockDowngradeDetector detector) {
        store.observeLock(
                () -> detector.recordReadLockAcquired(store.dataLock, "dataLock"),
                () -> detector.recordReadLockReleased(store.dataLock, "dataLock"),
                () -> detector.recordWriteLockAcquired(store.dataLock, "dataLock"),
                () -> detector.recordWriteLockReleased(store.dataLock, "dataLock"));
    }
}
