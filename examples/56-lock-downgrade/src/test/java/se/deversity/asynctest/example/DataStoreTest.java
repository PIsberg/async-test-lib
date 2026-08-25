package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.LockDowngradeDetector;
import se.deversity.asynctest.example.service.DataStore;
import org.junit.jupiter.api.BeforeEach;
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
 * WHAT THE DETECTOR REPORTS, AND WHY THIS EXAMPLE HAS NO @AsyncTest DEMONSTRATION:
 * LockDowngradeDetector reports one thing: a thread acquiring the write lock while
 * it already holds the read lock, which is the upgrade ReentrantReadWriteLock
 * cannot grant. It reports nothing at all about downgrades, correct or incorrect -
 * its own javadoc lists the correct downgrade as "not flagged" and does not mention
 * the incorrect one.
 *
 * This example used to carry a @Disabled @AsyncTest that recorded a write-acquire,
 * a write-release, a read-acquire and a read-release, and told the reader that
 * removing @Disabled would show the bad downgrade being detected. It does not, and
 * cannot: enabling it produced no report, three runs out of three. Rather than
 * change the example's subject to an upgrade - which is example 111, with its own
 * detector - the demonstration is gone and the detector's real behaviour is pinned
 * below, by tests that run on every build. The gap in the detector is issue #355.
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
     * And the incorrect downgrade is not a finding either, which is the gap. This test exists to
     * pin that gap rather than to celebrate it: if issue #355 is fixed, this assertion flips, and
     * flipping it is the point.
     */
    @Test
    void testLockDowngradeDetector_incorrectDowngrade_isAlsoSilent() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        wire(detector);

        store.updateAndRead("key", "value");

        assertFalse(detector.analyze().hasIssues(),
                "LockDowngradeDetector reports upgrades only; the unsafe downgrade this example "
                        + "is built around goes unreported. See issue #355. If this assertion "
                        + "starts failing, the detector learned to see it - update the example "
                        + "rather than the assertion.");
    }

    private void wire(LockDowngradeDetector detector) {
        store.observeLock(
                () -> detector.recordReadLockAcquired(store.dataLock, "dataLock"),
                () -> detector.recordReadLockReleased(store.dataLock, "dataLock"),
                () -> detector.recordWriteLockAcquired(store.dataLock, "dataLock"),
                () -> detector.recordWriteLockReleased(store.dataLock, "dataLock"));
    }
}
