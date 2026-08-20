package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lock bookkeeping every lock-aware detector reads.
 *
 * <p>These are the failure modes that would be invisible at the detector level: a set that keeps
 * a lock after release silences real findings, one that drops a lock too early resurrects the
 * false positives this whole mechanism exists to remove, and one that leaks across threads or
 * invocations does both in ways that depend on scheduling.
 */
@DisplayName("HeldLocks: the per-thread set detectors ask about")
class HeldLocksTest {

    @AfterEach
    void clearAfterEachTest() {
        HeldLocks.clear();
    }

    @Test
    @DisplayName("a declared lock is held until the guard closes")
    void holdingTracksTheLockForTheScope() {
        Object lock = new Object();
        assertFalse(HeldLocks.holds(lock), "nothing declared yet");

        try (var held = HeldLocks.holding(lock)) {
            assertTrue(HeldLocks.holds(lock), "inside the scope the lock is held");
        }

        assertFalse(HeldLocks.holds(lock),
                "The guard must release on close. A lock that outlives its scope is worse than "
                        + "no tracking at all: it makes later unguarded accesses look protected");
    }

    @Test
    @DisplayName("closing a guard twice does not release somebody else's acquisition")
    void guardIsIdempotent() {
        Object lock = new Object();
        HeldLocks.acquired(lock);
        var held = HeldLocks.holding(lock);
        held.close();
        held.close();

        assertTrue(HeldLocks.holds(lock),
                "The outer acquire is still outstanding. A guard that popped on every close "
                        + "would release a lock it never took, which is how try-with-resources "
                        + "nested inside a manual acquire would silently lose the outer one");
        HeldLocks.released(lock);
        assertFalse(HeldLocks.holds(lock), "and the outer release ends it");
    }

    @Test
    @DisplayName("reentrant acquisition needs matching releases")
    void reentrantAcquisitionIsCounted() {
        Object lock = new Object();
        HeldLocks.acquired(lock);
        HeldLocks.acquired(lock);
        HeldLocks.released(lock);

        assertTrue(HeldLocks.holds(lock),
                "Two acquires and one release still leaves the lock held - that is what "
                        + "reentrancy means, and treating the set as a set of distinct objects "
                        + "rather than a stack would drop it here");

        HeldLocks.released(lock);
        assertFalse(HeldLocks.holds(lock), "the second release ends it");
    }

    @Test
    @DisplayName("locks released out of order still leave the right set behind")
    void nonLifoReleaseKeepsTheRemainingLocks() {
        Object outer = new Object();
        Object inner = new Object();
        HeldLocks.acquired(outer);
        HeldLocks.acquired(inner);

        // j.u.c. locks have no nesting requirement, so this is legal and must not corrupt.
        HeldLocks.released(outer);

        assertFalse(HeldLocks.holds(outer), "the outer one is gone");
        assertTrue(HeldLocks.holds(inner),
                "and the inner one is not. Popping blindly from the top would have discarded "
                        + "the wrong entry and left a released lock in the set");
    }

    @Test
    @DisplayName("an unmatched release is ignored rather than thrown")
    void unmatchedReleaseIsIgnored() {
        HeldLocks.released(new Object());
        HeldLocks.released(null);
        HeldLocks.acquired(null);

        assertFalse(HeldLocks.holds(new Object()),
                "Bookkeeping mistakes in a test must never be the reason that test fails: this "
                        + "is diagnostic state, not a correctness contract on the user's code");
    }

    @Test
    @DisplayName("the set is per thread, and not inherited by threads started inside it")
    void locksDoNotLeakToOtherThreads() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        boolean[] seenByChild = new boolean[1];
        CountDownLatch done = new CountDownLatch(1);

        try (var held = HeldLocks.holding(lock)) {
            Thread child = new Thread(() -> {
                seenByChild[0] = HeldLocks.holds(lock);
                done.countDown();
            });
            child.start();
            done.await();
            child.join();
        }

        assertFalse(seenByChild[0],
                "A lock held by the thread that started a worker is not held by the worker. An "
                        + "InheritableThreadLocal here would hand every child a guard it never "
                        + "took and silence exactly the cross-thread races being looked for");
    }

    @Test
    @DisplayName("clear() drops everything, which is what uninstall relies on")
    void clearDropsTheWholeSet() {
        Object first = new Object();
        Object second = new Object();
        HeldLocks.acquired(first);
        HeldLocks.acquired(second);

        HeldLocks.clear();

        assertFalse(HeldLocks.holds(first), "first is gone");
        assertFalse(HeldLocks.holds(second),
                "and so is second. AsyncTestContext.uninstall() calls this so one invocation's "
                        + "declarations cannot be intersected into the next invocation's "
                        + "locksets, where they would silence real findings");
    }

    @Test
    @DisplayName("the intersection is the locks common to every access, not the union")
    void intersectionShrinksToWhatEveryAccessHeld() {
        Object instance = new Object();
        Object shared = new Object();
        Object onlyMine = new Object();

        HeldLocks.acquired(shared);
        HeldLocks.acquired(onlyMine);
        int[] first = HeldLocks.intersect(null, instance);
        assertEquals(2, first.length, "the first access seeds the set with everything held");

        HeldLocks.released(onlyMine);
        int[] second = HeldLocks.intersect(first, instance);
        assertEquals(1, second.length,
                "the second access held only one of them, so the other cannot be what protects "
                        + "the instance");

        HeldLocks.released(shared);
        int[] third = HeldLocks.intersect(second, instance);
        assertEquals(0, third.length,
                "and an access holding nothing empties it, which is the reportable state");
    }

    @Test
    @DisplayName("intersect returns the same array rather than reallocating when nothing dropped")
    void intersectSignalsNoChange() {
        Object instance = new Object();
        Object lock = new Object();

        HeldLocks.acquired(lock);
        int[] seeded = HeldLocks.intersect(null, instance);
        assertEquals(1, seeded.length, "seeded with the one declared lock");

        assertSame(seeded, HeldLocks.intersect(seeded, instance),
                "Still holding the same lock, so there is nothing to drop and the very same "
                        + "array comes back. That identity is what keeps a consistently guarded "
                        + "instance allocation-free on its hot path instead of copying an "
                        + "identical array on every access");
    }

    @Test
    @DisplayName("the instance's own monitor counts without being declared")
    void ownMonitorJoinsTheSetForFree() {
        Object instance = new Object();

        synchronized (instance) {
            int[] seeded = HeldLocks.intersect(null, instance);
            assertEquals(1, seeded.length,
                    "synchronized(instance) is the idiom that needs no declaration - "
                            + "Thread.holdsLock can answer for the tracked instance itself, which "
                            + "is the one lock a detector can always name");
        }

        int[] afterRelease = HeldLocks.intersect(new int[] {System.identityHashCode(instance)},
                instance);
        assertEquals(0, afterRelease.length,
                "and outside the block it is no longer held, so the set empties");
    }
}
