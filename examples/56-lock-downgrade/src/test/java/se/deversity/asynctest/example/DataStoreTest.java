package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DataStore demonstrating the LockDowngradeDetector.
 *
 * The concurrent test shows how releasing the write lock before acquiring the
 * read lock is flagged as an incorrect downgrade sequence.
 */
class DataStoreTest {

    private DataStore store;

    @BeforeEach
    void setUp() {
        store = new DataStore();
    }

    @Test
    void test_singleThread_updateAndRead_works() {
        String result = store.updateAndRead("key1", "value1");
        assertNotNull(result);
    }

    @Test
    void test_singleThread_read_returnsNull_whenMissing() {
        assertNull(store.read("missing"));
    }

    @Disabled("Remove @Disabled to see bug detected by LockDowngradeDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLockDowngrade = true)
    void test_concurrent_detectsBug() {
        // Record the incorrect downgrade sequence: write acquired → write released → read acquired
        AsyncTestContext.lockDowngradeMonitor()
                .recordWriteLockAcquired(store.dataLock, "dataLock");
        AsyncTestContext.lockDowngradeMonitor()
                .recordWriteLockReleased(store.dataLock, "dataLock");
        // Gap here — another thread can write before we acquire the read lock
        AsyncTestContext.lockDowngradeMonitor()
                .recordReadLockAcquired(store.dataLock, "dataLock");
        AsyncTestContext.lockDowngradeMonitor()
                .recordReadLockReleased(store.dataLock, "dataLock");

        store.updateAndRead("shared-key", "value-" + Thread.currentThread().getId());
    }
}
