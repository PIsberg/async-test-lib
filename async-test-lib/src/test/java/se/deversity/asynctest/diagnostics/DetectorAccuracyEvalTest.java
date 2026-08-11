package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector-accuracy eval: buggy code versus its correctly synchronized twin, with the
 * outcome of every pair pinned so the published numbers cannot drift from the code.
 *
 * <p><strong>Why this exists.</strong> The per-detector unit tests prove each analyzer's
 * arithmetic, and {@link se.deversity.asynctest.DetectionCoverageTest} proves which detectors
 * are reachable from a bare {@code @AsyncTest}. Neither answers the question an adopter
 * actually has: when a detector fires, was the code wrong? These pairs answer it in both
 * directions, and {@code docs/analysis/detector-accuracy-eval.md} publishes the table this
 * class enforces.
 *
 * <p><strong>The false-positive assertions are deliberate</strong>, in the
 * {@code DetectionCoverageTest} tradition of writing a limitation down and checking it
 * instead of assuming it away. Most shared-instance detectors reduce their input to "how
 * many threads touched this object" and carry no representation of locks, so a correctly
 * synchronized twin records the identical event stream and fires the identical finding.
 * If one of those assertions fails because a detector went <em>silent</em> on its safe
 * twin, that is good news: the detector gained synchronization awareness. Flip the
 * assertion and update the eval doc in the same change.
 */
@DisplayName("Detector accuracy eval: buggy code vs synchronized twin")
class DetectorAccuracyEvalTest {

    /** Runs the two actions on two freshly started threads that collide on a barrier,
     * then joins both, so every recording genuinely happens from distinct live threads. */
    private static void onTwoThreads(Runnable first, Runnable second) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable sync1 = () -> { await(barrier); first.run(); };
        Runnable sync2 = () -> { await(barrier); second.run(); };
        Thread t1 = new Thread(sync1);
        Thread t2 = new Thread(sync2);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- RaceConditionDetector ----

    @Test
    @DisplayName("race: unsynchronized concurrent writes fire (true positive)")
    void raceDetectorFiresOnUnsynchronizedWrites() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Runnable increment = () -> {
            detector.recordFieldRead(shared, "value");
            shared.value++;
            detector.recordFieldWrite(shared, "value");
        };
        onTwoThreads(increment, increment);

        assertTrue(detector.analyze().hasIssues(),
                "Two threads incrementing an unsynchronized int is the canonical lost "
                        + "update; a race detector that misses it detects nothing");
    }

    @Test
    @DisplayName("race: the synchronized twin fires identically (pinned false positive)")
    void raceDetectorFiresOnTheSynchronizedTwinToo() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object lock = new Object();
        Runnable increment = () -> {
            synchronized (lock) {
                detector.recordFieldRead(shared, "value");
                shared.value++;
                detector.recordFieldWrite(shared, "value");
            }
        };
        onTwoThreads(increment, increment);

        assertTrue(detector.analyze().hasIssues(),
                "PINNED FALSE POSITIVE: the increments are fully lock-protected and the "
                        + "code is correct, but the guard is a lock object other than the "
                        + "shared instance itself, which the holdsLock probe cannot see "
                        + "(the guard-on-self twin below is the recognized case). If this "
                        + "went silent, the detector gained general lock awareness - flip "
                        + "this assertion and update detector-accuracy-eval.md");
    }

    @Test
    @DisplayName("race: the synchronized(shared) twin stays silent (true negative since guard-on-self)")
    void raceDetectorStaysSilentWhenGuardedByTheSharedObjectsOwnMonitor() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Runnable increment = () -> {
            synchronized (shared) {
                detector.recordFieldRead(shared, "value");
                shared.value++;
                detector.recordFieldWrite(shared, "value");
            }
        };
        onTwoThreads(increment, increment);

        assertFalse(detector.analyze().hasIssues(),
                "Every access held the shared object's own monitor, so the accesses are "
                        + "mutually excluded and ordered by it; firing here would flag the "
                        + "most common correct guarding idiom in Java");
    }

    // ---- AtomicityValidator ----

    @Test
    @DisplayName("atomicity: unsynchronized read-modify-write fires (true positive)")
    void atomicityValidatorFiresOnUnsynchronizedReadModifyWrite() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        Runnable readModifyWrite = () -> {
            validator.recordFieldAccess("balance", shared.value, false);
            shared.value++;
            validator.recordFieldAccess("balance", shared.value, true);
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertTrue(validator.analyze().hasIssues(),
                "Mixed read/write access to one field from two threads is the "
                        + "check-then-act window this validator exists to flag");
    }

    @Test
    @DisplayName("atomicity: the synchronized twin fires identically (pinned false positive)")
    void atomicityValidatorFiresOnTheSynchronizedTwinToo() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        Object lock = new Object();
        Runnable readModifyWrite = () -> {
            synchronized (lock) {
                validator.recordFieldAccess("balance", shared.value, false);
                shared.value++;
                validator.recordFieldAccess("balance", shared.value, true);
            }
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertTrue(validator.analyze().hasIssues(),
                "PINNED FALSE POSITIVE: the read-modify-write is atomic under the lock, "
                        + "but the validator reads only (threadId, isWrite) and cannot tell "
                        + "a guarded compound operation from a racy one. This matters "
                        + "doubly because the agent auto-feeds this validator from woven "
                        + "JavaBean accessors. If this went silent, flip the assertion and "
                        + "update detector-accuracy-eval.md");
    }

    // ---- SharedMessageDigestDetector ----

    @Test
    @DisplayName("digest: unsynchronized shared MessageDigest fires (true positive)")
    void digestDetectorFiresOnUnsynchronizedSharing() throws InterruptedException {
        SharedMessageDigestDetector detector = new SharedMessageDigestDetector();
        MessageDigest digest = sha256();
        Runnable update = () -> {
            digest.update((byte) 1);
            detector.recordAccess(digest, "shared-digest", Thread.currentThread());
        };
        onTwoThreads(update, update);

        assertTrue(detector.analyze().hasIssues(),
                "MessageDigest is genuinely not thread-safe; unsynchronized concurrent "
                        + "update() interleaves hash state");
    }

    @Test
    @DisplayName("digest: the synchronized(digest) twin stays silent (true negative since guard-on-self)")
    void digestDetectorStaysSilentOnTheSynchronizedSelfTwin() throws InterruptedException {
        SharedMessageDigestDetector detector = new SharedMessageDigestDetector();
        MessageDigest digest = sha256();
        Runnable update = () -> {
            synchronized (digest) {
                digest.update((byte) 1);
                detector.recordAccess(digest, "shared-digest", Thread.currentThread());
            }
        };
        onTwoThreads(update, update);

        assertFalse(detector.analyze().hasIssues(),
                "TRUE NEGATIVE since guard-on-self awareness: every access held the "
                        + "digest's own monitor, which is exactly the synchronized(digest) "
                        + "idiom, so the sharing is recognized as guarded. Firing here "
                        + "means the holdsLock probe regressed");
    }

    @Test
    @DisplayName("digest: an external-lock twin still fires (pinned false positive)")
    void digestDetectorStillFiresWhenGuardedByAnExternalLock() throws InterruptedException {
        SharedMessageDigestDetector detector = new SharedMessageDigestDetector();
        MessageDigest digest = sha256();
        Object lock = new Object();
        Runnable update = () -> {
            synchronized (lock) {
                digest.update((byte) 1);
                detector.recordAccess(digest, "shared-digest", Thread.currentThread());
            }
        };
        onTwoThreads(update, update);

        assertTrue(detector.analyze().hasIssues(),
                "PINNED FALSE POSITIVE: the guard is a separate lock object, which the "
                        + "holdsLock probe on the instance cannot see. If this went silent "
                        + "the detector gained general lock awareness - flip this assertion "
                        + "and update detector-accuracy-eval.md");
    }

    // ---- SharedStatefulCryptoDetector ----

    @Test
    @DisplayName("stateful crypto: unsynchronized shared Mac fires (true positive)")
    void statefulCryptoFiresOnUnsynchronizedSharing() throws Exception {
        SharedStatefulCryptoDetector detector = new SharedStatefulCryptoDetector();
        Mac mac = Mac.getInstance("HmacSHA256");
        Runnable use = () -> detector.recordAccess(mac, "shared-mac", Thread.currentThread());
        onTwoThreads(use, use);

        assertTrue(detector.analyze().hasIssues(),
                "Mac folds bytes from both callers into one running digest; "
                        + "unsynchronized sharing breaks integrity silently");
    }

    @Test
    @DisplayName("stateful crypto: the synchronized(mac) twin stays silent (true negative since guard-on-self)")
    void statefulCryptoStaysSilentOnTheSynchronizedSelfTwin() throws Exception {
        SharedStatefulCryptoDetector detector = new SharedStatefulCryptoDetector();
        Mac mac = Mac.getInstance("HmacSHA256");
        Runnable use = () -> {
            synchronized (mac) {
                detector.recordAccess(mac, "shared-mac", Thread.currentThread());
            }
        };
        onTwoThreads(use, use);

        assertFalse(detector.analyze().hasIssues(),
                "Every access held the Mac's own monitor, so the init/update/doFinal "
                        + "sequences are mutually excluded; the guard-on-self idiom must "
                        + "not be flagged");
    }

    // ---- SharedSecureRandomDetector ----

    @Test
    @DisplayName("secure random: the documented-safe shared idiom reports, as a MEDIUM contention note")
    void secureRandomSharedIdiomReportsAtMedium() throws InterruptedException {
        SharedSecureRandomDetector detector = new SharedSecureRandomDetector();
        SecureRandom rng = new SecureRandom();
        Runnable draw = () -> {
            rng.nextInt();
            detector.recordAccess(rng, "shared-rng", Thread.currentThread());
        };
        onTwoThreads(draw, draw);

        SharedSecureRandomDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(),
                "The detector still reports the sharing - as an observation");
        assertEquals(IssueSeverity.MEDIUM, IssueSeverity.fromReport(report.toString()),
                "java.security.SecureRandom documents instances as safe for concurrent "
                        + "use; on JDK providers this finding is a contention note, and "
                        + "gating it above MEDIUM would fail builds over correct code");
    }

    // ---- LockOrderValidator ----

    @Test
    @DisplayName("lock order: an A->B / B->A inversion fires without needing a deadlock (true positive)")
    void lockOrderValidatorFiresOnInversion() {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        // Sequential on purpose: the union graph accumulates edges across threads, so the
        // inversion is reported structurally, with no risk of this test really deadlocking.
        acquireInOrder(validator, a, b);
        acquireInOrder(validator, b, a);

        assertTrue(validator.validateLockOrder().hasIssues(),
                "Both nesting directions were recorded; the cycle exists whether or not "
                        + "the schedule ever made it deadlock. Firing here, before the "
                        + "deadlock happens, is this validator's whole value");
    }

    @Test
    @DisplayName("lock order: consistent A->B ordering stays silent (true negative)")
    void lockOrderValidatorStaysSilentOnConsistentOrdering() {
        LockOrderValidator validator = new LockOrderValidator();
        Object a = new Object();
        Object b = new Object();
        acquireInOrder(validator, a, b);
        acquireInOrder(validator, a, b);

        assertFalse(validator.validateLockOrder().hasIssues(),
                "Consistent ordering is the fix for lock-order deadlocks; a validator "
                        + "that flags it would make the fix look as broken as the bug");
    }

    private static void acquireInOrder(LockOrderValidator validator, Object first, Object second) {
        validator.recordLockAcquisition(first);
        validator.recordLockAcquisition(second);
        validator.recordLockRelease(second);
        validator.recordLockRelease(first);
    }

    // ---- AtomicNonAtomicUpdateDetector ----

    @Test
    @DisplayName("atomic misuse: get-then-set fires (true positive)")
    void nonAtomicUpdateDetectorFiresOnGetThenSet() throws InterruptedException {
        AtomicNonAtomicUpdateDetector detector = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Runnable getThenSet = () -> {
            int current = counter.get();
            detector.recordGet(counter, "counter", Thread.currentThread());
            counter.set(current + 1);
            detector.recordSet(counter, "counter", Thread.currentThread());
        };
        onTwoThreads(getThenSet, getThenSet);

        assertTrue(detector.analyze().hasIssues(),
                "get() then set() on an atomic is a lost update between the two calls; "
                        + "the atomic type does not make the compound atomic");
    }

    @Test
    @DisplayName("atomic misuse: the CAS twin stays silent (true negative)")
    void nonAtomicUpdateDetectorStaysSilentOnCas() throws InterruptedException {
        AtomicNonAtomicUpdateDetector detector = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Runnable casLoop = () -> {
            int current;
            do {
                current = counter.get();
                detector.recordGet(counter, "counter", Thread.currentThread());
            } while (!counter.compareAndSet(current, current + 1));
            detector.recordCas(counter, "counter", Thread.currentThread());
        };
        onTwoThreads(casLoop, casLoop);

        assertFalse(detector.analyze().hasIssues(),
                "compareAndSet closes the get-to-write window; the per-thread state "
                        + "machine clears the pending get on recordCas, so the correct "
                        + "idiom is distinguishable from the broken one - this detector "
                        + "genuinely has both directions");
    }

    // ---- DeadlockDetector ----

    @Test
    @DisplayName("deadlock: ordered locking stays silent (true negative; the true-positive lives in DetectionCoverageTest)")
    void deadlockDetectorStaysSilentOnOrderedLocking() throws InterruptedException {
        DeadlockDetector detector = new DeadlockDetector();
        Object a = new Object();
        Object b = new Object();
        Runnable ordered = () -> {
            synchronized (a) {
                synchronized (b) {
                    // consistent A->B nesting: deadlock-free by construction
                }
            }
        };
        onTwoThreads(ordered, ordered);

        assertFalse(detector.analyze().hasIssues(),
                "ThreadMXBean.findDeadlockedThreads() confirms a live circular wait or "
                        + "nothing; correct code cannot trip it. The firing direction - a "
                        + "real deadlock, no instrumentation - is pinned by "
                        + "DetectionCoverageTest.deadlockIsReportedWithoutAnyInstrumentation");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static class Counter {
        int value;
    }
}
