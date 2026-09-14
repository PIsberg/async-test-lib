package se.deversity.asynctest.diagnostics;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock held twice at one access must not crash the intersection that asks whether a lock was
 * held at every access (#605).
 *
 * <p>A held-lock set can name the same lock more than once. A {@code synchronized} method on the
 * receiver carries the receiver's hash as both its own monitor and the method's monitor, and a
 * lock acquired reentrantly sits on the thread's lock stack twice, so its registered members
 * repeat. Both intersections sized their output by the shorter input and copied every match from
 * the longer one, so {@code {M, M}} against {@code {M}} wrote a second element into an array of
 * one. In {@code AtomicityValidator} the throw escaped {@code analyze()}, where the runner's
 * failure policy skips the detector with one stderr line and the run reports nothing for it,
 * which reads exactly like a clean run. It showed up in the corpus eval as
 * {@code ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1}, only when the access
 * holding the lock twice happened to drain before the one holding it once.
 */
class DuplicateLockIntersectionTest {

    private static final int IDENTITY = 605;

    private static final int NOT_A_CONSTANT = Integer.MIN_VALUE;

    private static final String FIELD = "Chunk.state";

    /** Identity hashes standing in for monitors; the recording API carries only the hash. */
    private static final int BUILDER_LOCK = 1001;

    private static final int OWNER_LOCK = 2002;

    private static final int OTHER_LOCK = 3003;

    private static long fingerprintHolding(Object... locks) {
        HeldLocks.Guard[] guards = new HeldLocks.Guard[locks.length];
        for (int i = 0; i < locks.length; i++) {
            guards[i] = HeldLocks.holding(locks[i]);
        }
        long fingerprint = HeldLocks.lockFingerprint(false);
        for (int i = locks.length - 1; i >= 0; i--) {
            guards[i].close();
        }
        return fingerprint;
    }

    /**
     * Builds an object under one lock, hands it over through a take, and leaves the per-instance
     * lockset collapsed, so the per-generation excuse (#555) is what decides the finding.
     */
    private static AtomicityValidator builtUnderOneLockThenTaken() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 1L, 0L,
                BUILDER_LOCK, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordOwnershipTaken(IDENTITY, 2L);
        return validator;
    }

    @Test
    @DisplayName("a synchronized method's monitor counted twice does not crash the generation excuse")
    void sameMonitorAsOwnAndMethodMonitor() {
        AtomicityValidator validator = builtUnderOneLockThenTaken();
        // Inside a synchronized method of the receiver: own monitor and method monitor are the same.
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 3L, 0L,
                OWNER_LOCK, OWNER_LOCK, false, NOT_A_CONSTANT, IDENTITY);
        // Under synchronized (this) in an unsynchronized method: the own monitor alone.
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 2L, 0L,
                OWNER_LOCK, 0, false, NOT_A_CONSTANT, IDENTITY);

        AtomicityValidator.AtomicityReport report =
                assertDoesNotThrow(validator::analyzeAtomicity);
        assertFalse(report.hasIssues(),
                "after the take every access held the owner's monitor, which is the hand-off the"
                        + " generation excuse exists for: " + report);
    }

    @Test
    @DisplayName("the same shape with a second owner lock that disagrees still reports")
    void sameMonitorTwiceThenADisagreeingLockStillReports() {
        AtomicityValidator validator = builtUnderOneLockThenTaken();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 3L, 0L,
                OWNER_LOCK, OWNER_LOCK, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 2L, 0L,
                OWNER_LOCK, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 4L, 0L,
                OTHER_LOCK, 0, false, NOT_A_CONSTANT, IDENTITY);

        AtomicityValidator.AtomicityReport report =
                assertDoesNotThrow(validator::analyzeAtomicity);
        assertTrue(report.hasIssues(),
                "thread 4 wrote under a lock no other access in its generation held");
    }

    @Test
    @DisplayName("a lock acquired reentrantly repeats in its fingerprint's members without crashing")
    void reentrantAcquisitionInTheFingerprint() {
        Object owner = new Object();
        long twice = fingerprintHolding(owner, owner);
        long once = fingerprintHolding(owner);

        AtomicityValidator validator = builtUnderOneLockThenTaken();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 3L, twice,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 2L, once,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        AtomicityValidator.AtomicityReport report =
                assertDoesNotThrow(validator::analyzeAtomicity);
        assertFalse(report.hasIssues(),
                "both post-take accesses held the same lock, however many times: " + report);
    }

    @Test
    @DisplayName("a reentrant hold and a disjoint lock after the take still report")
    void reentrantAcquisitionThenDisjointLockStillReports() {
        Object owner = new Object();
        Object other = new Object();
        long twice = fingerprintHolding(owner, owner);
        long once = fingerprintHolding(owner);
        long disjoint = fingerprintHolding(other);

        AtomicityValidator validator = builtUnderOneLockThenTaken();
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 3L, twice,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks(FIELD, null, false, 2L, once,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);
        validator.recordFieldAccessUnderLocks(FIELD, null, true, 4L, disjoint,
                0, 0, false, NOT_A_CONSTANT, IDENTITY);

        AtomicityValidator.AtomicityReport report =
                assertDoesNotThrow(validator::analyzeAtomicity);
        assertTrue(report.hasIssues(), "thread 4's lock was never held by the others");
    }

    private static final class Counter {
        int value;
    }

    @Test
    @DisplayName("RaceConditionDetector: a lock held reentrantly by one writer still guards the field")
    void raceConditionReentrantHoldGuards() throws InterruptedException {
        var detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object lock = new Object();

        inOrderOnTwoThreads(
                () -> {
                    try (var outer = HeldLocks.holding(lock); var inner = HeldLocks.holding(lock)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                },
                () -> {
                    try (var held = HeldLocks.holding(lock)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                });

        RaceConditionDetector.RaceConditionReport report =
                assertDoesNotThrow(detector::analyze);
        assertFalse(report.hasIssues(), "both writers held the same lock: " + report);
    }

    @Test
    @DisplayName("RaceConditionDetector: a reentrant hold against a disjoint lock still races")
    void raceConditionReentrantHoldAgainstDisjointLockRaces() throws InterruptedException {
        var detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object lock = new Object();
        Object other = new Object();

        inOrderOnTwoThreads(
                () -> {
                    try (var outer = HeldLocks.holding(lock); var inner = HeldLocks.holding(lock)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                },
                () -> {
                    try (var held = HeldLocks.holding(other)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                });

        RaceConditionDetector.RaceConditionReport report =
                assertDoesNotThrow(detector::analyze);
        assertTrue(report.hasIssues(), "no lock was held at both writes");
    }

    /** Runs {@code first} to completion on one thread, then {@code second} on another. */
    private static void inOrderOnTwoThreads(Runnable first, Runnable second)
            throws InterruptedException {
        CountDownLatch firstDone = new CountDownLatch(1);
        Thread a = new Thread(() -> {
            first.run();
            firstDone.countDown();
        });
        Thread b = new Thread(() -> {
            try {
                firstDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            second.run();
        });
        a.start();
        b.start();
        a.join();
        b.join();
    }
}
