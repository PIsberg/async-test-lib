package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code registerLock()} / {@code registerResource()} installed a <em>fresh</em> state object with
 * {@code map.put(...)}, wiping the acquire/release counts accumulated so far.
 *
 * <p>That matters because the documented way to use these detectors — shown in the class's own
 * Javadoc example — is to call {@code registerLock()} inside the {@code @AsyncTest} method body.
 * The runner executes that body {@code threads × invocations} times (10 × 100 by default) against
 * the same shared lock. So every invocation after the first discarded whatever the previous ones
 * had recorded.
 *
 * <p>The consequence is a missed leak: a thread acquires the lock and never releases it, then the
 * next invocation calls {@code registerLock()} again and the unmatched acquire is erased. At
 * analysis time {@code acquires > releases} is false and nothing is reported.
 *
 * <p>Registration must be idempotent — the first registration establishes the state, later ones
 * find it.
 */
class LeakRegistrationResetTest {

    @Test
    void aLeakedLockSurvivesTheNextInvocationsReRegistration() {
        LockLeakDetector detector = new LockLeakDetector();
        Lock lock = new ReentrantLock();

        // Invocation 1: the test body registers the lock, acquires it, and leaks it.
        detector.registerLock(lock, "leaky");
        detector.recordLockAcquired(lock, "leaky");
        // ...no matching recordLockReleased — this is the bug the detector must catch.

        // Invocation 2: the same test body runs again and registers the same lock again.
        detector.registerLock(lock, "leaky");

        LockLeakDetector.LockLeakReport report = detector.analyze();

        assertFalse(report.lockLeaks.isEmpty(),
            "the unreleased acquire must survive re-registration and be reported as a leak");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }

    /** A balanced lock stays clean no matter how often the body re-registers it. */
    @Test
    void aBalancedLockIsNotReportedAsALeak() {
        LockLeakDetector detector = new LockLeakDetector();
        Lock lock = new ReentrantLock();

        for (int invocation = 0; invocation < 3; invocation++) {
            detector.registerLock(lock, "balanced");
            detector.recordLockAcquired(lock, "balanced");
            detector.recordLockReleased(lock, "balanced");
        }

        assertTrue(detector.analyze().lockLeaks.isEmpty(),
            "every acquire was released — nothing leaked: " + detector.analyze().lockLeaks);
    }

    @Test
    void aLeakedResourceSurvivesTheNextInvocationsReRegistration() {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        Object resource = new Object();

        detector.registerResource(resource, "conn", "Connection");
        detector.recordResourceOpened(resource, "conn");
        // ...never closed.

        detector.registerResource(resource, "conn", "Connection");

        ResourceLeakDetector.ResourceLeakReport report = detector.analyze();

        assertTrue(report.hasIssues(),
            "the unclosed resource must survive re-registration and be reported");
    }
}
