package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hold recorded as taken and never given back is reported, whichever API recorded it.
 *
 * <p><strong>Why this exists.</strong> {@code ReentrantLockDetector} has
 * {@code recordLockAcquired} and {@code recordLockReleased}, keeps counts from them, prints those
 * counts, and gates on neither: {@code hasIssues()} is timeouts or starvation. A caller who
 * instrumented the obvious pair and whose code leaked a hold got a clean report and a
 * {@code failOn} gate that never tripped. That was issue #368.
 *
 * <p>Adding the finding to that detector would have duplicated {@code LockLeakDetector}, which
 * already reports both the imbalance and a lock still held at analysis, and duplicate findings
 * under two names is what #361 was about. So the records are forwarded instead, and the finding
 * comes out once, under the name that owns it.
 */
class LeakedHoldReportedOnceTest {

    @Test
    @DisplayName("a leak recorded through ReentrantLockDetector is reported by LockLeakDetector")
    void withBothEnabled_theLeakIsReportedByTheDetectorThatOwnsIt() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .detectReentrantLockIssues(true)
                .detectLockLeaks(true)
                .build();
        DetectorRegistry registry = new DetectorRegistry(cfg);
        assertNotNull(registry.reentrantLockDetector);
        assertNotNull(registry.lockLeakDetector);

        ReentrantLock lock = new ReentrantLock();
        registry.reentrantLockDetector.registerLock(lock, "counter-lock");
        registry.reentrantLockDetector.recordLockAcquired(lock, Thread.currentThread().getName());
        // and no matching release: the hold is leaked

        Map<String, String> findings = registry.analyzeAllNamed();
        assertTrue(findings.containsKey("LockLeakDetector"),
                "the detector that reports leaks must report this one, even though the caller "
                        + "never touched its API. Findings: " + findings.keySet());
        assertTrue(findings.get("LockLeakDetector").contains("counter-lock"),
                "and under the name the caller registered: " + findings.get("LockLeakDetector"));
    }

    @Test
    @DisplayName("a balanced pair is not reported as a leak")
    void aBalancedPairStaysSilent() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .detectReentrantLockIssues(true)
                .detectLockLeaks(true)
                .build();
        DetectorRegistry registry = new DetectorRegistry(cfg);
        assertNotNull(registry.reentrantLockDetector);

        ReentrantLock lock = new ReentrantLock();
        registry.reentrantLockDetector.registerLock(lock, "counter-lock");
        registry.reentrantLockDetector.recordLockAcquired(lock, Thread.currentThread().getName());
        registry.reentrantLockDetector.recordLockReleased(lock, Thread.currentThread().getName());

        Map<String, String> findings = registry.analyzeAllNamed();
        assertFalse(findings.containsKey("LockLeakDetector"),
                "every acquire was released and the lock is free, so a finding here would be a "
                        + "false positive on correct code. Findings: " + findings.keySet());
    }

    @Test
    @DisplayName("ReentrantLockDetector still reports only what it gates on")
    void theForwardingDoesNotGiveTheForwarderANewFinding() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(false)
                .detectReentrantLockIssues(true)
                .detectLockLeaks(true)
                .build();
        DetectorRegistry registry = new DetectorRegistry(cfg);
        assertNotNull(registry.reentrantLockDetector);

        ReentrantLock lock = new ReentrantLock();
        registry.reentrantLockDetector.registerLock(lock, "counter-lock");
        registry.reentrantLockDetector.recordLockAcquired(lock, Thread.currentThread().getName());

        Map<String, String> findings = registry.analyzeAllNamed();
        assertFalse(findings.containsKey("ReentrantLockDetector"),
                "the leak is one finding, not two. This detector gates on timeouts and "
                        + "starvation, and forwarding must not quietly turn it into a second "
                        + "voice on the same condition. Findings: " + findings.keySet());
    }
}
