package se.deversity.asynctest.diagnostics;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RaceConditionDetector} asks whether some lock was held at every access, not whether every
 * access held the same set of locks (#570).
 *
 * <p>It compared digests of each access's lock set for equality. A thread writing under
 * {@code synchronized (shared)} and another writing under {@code synchronized (shared)} plus a
 * declared lock of its own are both excluded by the shared monitor, yet the two digests differ and
 * the field was reported. The catalog described the intersection; this pins it.
 */
class RaceConditionLocksetTest {

    private static final class Counter {
        int value;
    }

    @Test
    @DisplayName("a lock common to every access guards the field, whatever else one thread holds")
    void aCommonLockGuardsEvenWhenOneThreadHoldsMore() throws InterruptedException {
        var detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object extra = new Object();

        runBoth(
                () -> {
                    synchronized (shared) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                },
                () -> {
                    synchronized (shared) {
                        try (var held = HeldLocks.holding(extra)) {
                            detector.recordFieldWrite(shared, "value");
                            shared.value++;
                        }
                    }
                });

        assertFalse(detector.analyze().hasIssues(),
                "both writers held shared's own monitor, which excludes them from each other: "
                        + detector.analyze());
    }

    @Test
    @DisplayName("two threads each holding a different lock exclude nothing and still race")
    void disjointLocksStillRace() throws InterruptedException {
        var detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object first = new Object();
        Object second = new Object();

        runBoth(
                () -> {
                    try (var held = HeldLocks.holding(first)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                },
                () -> {
                    try (var held = HeldLocks.holding(second)) {
                        detector.recordFieldWrite(shared, "value");
                        shared.value++;
                    }
                });

        assertTrue(detector.analyze().hasIssues(),
                "no lock was held at both writes, so nothing kept them apart");
    }

    @Test
    @DisplayName("a read under a shared lock and a write under another lock race")
    void readUnderOneLockAndWriteUnderAnotherRace() throws InterruptedException {
        var detector = new RaceConditionDetector();
        Counter shared = new Counter();
        Object first = new Object();
        Object second = new Object();

        runBoth(
                () -> {
                    try (var held = HeldLocks.holding(first)) {
                        detector.recordFieldWrite(shared, "value");
                    }
                },
                () -> {
                    try (var held = HeldLocks.holding(second)) {
                        detector.recordFieldRead(shared, "value");
                        detector.recordFieldWrite(shared, "value");
                    }
                });

        assertTrue(detector.analyze().hasIssues());
    }

    private static void runBoth(Runnable one, Runnable two) throws InterruptedException {
        CountDownLatch firstDone = new CountDownLatch(1);
        Thread a = new Thread(() -> {
            one.run();
            firstDone.countDown();
        });
        Thread b = new Thread(() -> {
            try {
                firstDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            two.run();
        });
        a.start();
        b.start();
        a.join();
        b.join();
    }
}
