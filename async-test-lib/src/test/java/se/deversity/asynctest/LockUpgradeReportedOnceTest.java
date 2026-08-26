package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.LockDowngradeDetector;
import se.deversity.asynctest.diagnostics.LockUpgradeDeadlockDetector;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One read-to-write upgrade is one finding, whichever detectors are switched on.
 *
 * <p><strong>Why this exists.</strong> {@link LockDowngradeDetector} and
 * {@link LockUpgradeDeadlockDetector} both report a thread acquiring the write lock while it
 * already holds the read lock. With {@code detectAll}, which is the default, both are on, and a
 * run that fed both reported the same condition twice under two names, one of which does not
 * describe it. That was issue #361.
 *
 * <p>Deleting the finding from {@link LockDowngradeDetector} on its own would have been worse
 * than the duplicate. The two have separate recording APIs, so a caller who instruments only the
 * downgrade detector and never calls {@code LockUpgradeDeadlockDetector.record*} would go from a
 * finding to a clean report with nothing to say why: the silent green this repository cares most
 * about. So the downgrade detector forwards what it records to the upgrade detector when the
 * registry has both, and stands down from reporting it; when it is the only one enabled it keeps
 * reporting, because nothing else will.
 *
 * <p>Both directions are pinned below, because either alone would pass a broken implementation:
 * deleting the finding satisfies the first, and doing nothing satisfies the second.
 */
class LockUpgradeReportedOnceTest {

    @Test
    void withBothEnabled_theUpgradeIsReportedOnce_byTheDetectorThatOwnsIt() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .detectLockDowngrade(true)
                .detectLockUpgradeDeadlock(true)
                .build();
        DetectorRegistry registry = new DetectorRegistry(cfg);
        assertNotNull(registry.lockDowngradeDetector);
        assertNotNull(registry.lockUpgradeDeadlockDetector);

        upgradeThroughTheDowngradeDetector(registry.lockDowngradeDetector);

        Map<String, String> findings = registry.analyzeAllNamed();
        assertTrue(findings.containsKey("LockUpgradeDeadlockDetector"),
                "the detector named for the condition must report it, whichever recording API "
                        + "the caller used. Findings: " + findings.keySet());
        assertFalse(findings.containsKey("LockDowngradeDetector"),
                "the same upgrade must not be reported a second time under a name that describes "
                        + "the opposite operation. Findings: " + findings.keySet());
    }

    @Test
    void withOnlyTheDowngradeDetectorEnabled_theUpgradeIsStillReported() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .detectLockDowngrade(true)
                .build();
        DetectorRegistry registry = new DetectorRegistry(cfg);
        assertNotNull(registry.lockDowngradeDetector);

        upgradeThroughTheDowngradeDetector(registry.lockDowngradeDetector);

        Map<String, String> findings = registry.analyzeAllNamed();
        assertEquals(1, findings.size(),
                "exactly one finding, from the only detector that is on. Findings: "
                        + findings.keySet());
        assertTrue(findings.containsKey("LockDowngradeDetector"),
                "with nothing else enabled this detector must keep reporting the upgrade: going "
                        + "quiet here would turn a real finding into a clean report. Findings: "
                        + findings.keySet());
        assertTrue(findings.get("LockDowngradeDetector").contains("upgrade"),
                "and the report must still say what it saw: "
                        + findings.get("LockDowngradeDetector"));
    }

    /** The read-to-write upgrade, recorded only through {@link LockDowngradeDetector}'s API. */
    private static void upgradeThroughTheDowngradeDetector(LockDowngradeDetector detector) {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        lock.readLock().lock();
        try {
            detector.recordReadLockAcquired(lock, "shared-lock");
            // tryLock rather than lock: the upgrade this records is the one that would never
            // return, so the test must not actually wait for it.
            assertFalse(lock.writeLock().tryLock(),
                    "a thread holding the read lock cannot be granted the write lock");
            detector.recordWriteLockAcquired(lock, "shared-lock");
        } finally {
            detector.recordReadLockReleased(lock, "shared-lock");
            lock.readLock().unlock();
        }
    }
}
