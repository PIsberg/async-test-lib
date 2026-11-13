package se.deversity.asynctest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An object stored by a woven {@code compareAndSet} and read by a woven {@code get} is ordered,
 * however soon after the swap the get runs (#1328).
 *
 * <p>The hooks release a successful swap after it, because a failed one must publish nothing and
 * a volatile field's release cannot be withdrawn (#742). A get that returned the value in the
 * instant between the swap and its release found nothing to take, and the reader's use of the
 * object read as racing the writer's initialisation of it.
 */
class SwapPublicationWindowTest {

    static final class Box {
        int value;
    }

    /** A reference slot of one kind: a swap from empty, and a read. */
    private interface Slot {

        boolean swapIn(Object box);

        Object read();
    }

    private static Slot atomicReference() {
        AtomicReference<Object> slot = new AtomicReference<>();
        return new Slot() {
            @Override
            public boolean swapIn(Object box) {
                return TelemetryRegistry.compareAndSetAtomicReference(slot, null, box);
            }

            @Override
            public Object read() {
                return TelemetryRegistry.getAtomicReference(slot);
            }
        };
    }

    private static Slot atomicReferenceArray() {
        AtomicReferenceArray<Object> slots = new AtomicReferenceArray<>(4);
        return new Slot() {
            @Override
            public boolean swapIn(Object box) {
                return TelemetryRegistry.compareAndSetReferenceArray(slots, 2, null, box);
            }

            @Override
            public Object read() {
                return TelemetryRegistry.getReferenceArray(slots, 2);
            }
        };
    }

    private static Slot atomicStampedReference() {
        AtomicStampedReference<Object> slot = new AtomicStampedReference<>(null, 0);
        return new Slot() {
            @Override
            public boolean swapIn(Object box) {
                return TelemetryRegistry.compareAndSetStampedReference(slot, null, box, 0, 1);
            }

            @Override
            public Object read() {
                return TelemetryRegistry.getReferenceStampedReference(slot);
            }
        };
    }

    /** Rounds per case: enough for a reader spinning on the slot to land in the window often. */
    private static final int ROUNDS = 2_000;

    /**
     * One round: the writer initialises a box and swaps it into the slot, the reader spins on the
     * slot and uses the box the moment a read returns it. {@return whether that was reported}
     */
    private static boolean lazyInitialisationReported(Slot slot) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Thread reader = new Thread(() -> {
            Object got;
            while ((got = slot.read()) == null) {
                Thread.onSpinWait();
            }
            Box box = (Box) got;
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        Thread writer = new Thread(() -> {
            Box box = new Box();
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            slot.swapIn(box);
        });
        reader.start();
        writer.start();
        reader.join();
        writer.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    private static int roundsReported(Supplier<Slot> kind) throws InterruptedException {
        int reported = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (lazyInitialisationReported(kind.get())) {
                reported++;
            }
        }
        return reported;
    }

    private static final String ORDERED = "The writer initialised the box and only then swapped it "
            + "in; the reader reached it through a read that returned it. A volatile write and the "
            + "read that saw it order the two, whether the read ran a second or a nanosecond after "
            + "the swap. Rounds reported, of " + ROUNDS;

    @Test
    @DisplayName("an AtomicReference get right after the compareAndSet that stored its value is ordered after it")
    void aGetRightAfterTheSwapIsOrdered() throws InterruptedException {
        assertEquals(0, roundsReported(SwapPublicationWindowTest::atomicReference), ORDERED);
    }

    @Test
    @DisplayName("so is an AtomicReferenceArray element's, a slot named by its index")
    void anArrayElementReadRightAfterTheSwapIsOrdered() throws InterruptedException {
        assertEquals(0, roundsReported(SwapPublicationWindowTest::atomicReferenceArray), ORDERED);
    }

    @Test
    @DisplayName("and an AtomicStampedReference's")
    void aStampedReadRightAfterTheSwapIsOrdered() throws InterruptedException {
        assertEquals(0, roundsReported(SwapPublicationWindowTest::atomicStampedReference), ORDERED);
    }

    @Test
    @DisplayName("a swap that failed still publishes nothing to a get of the same object")
    void aFailedSwapStillPublishesNothing() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        AtomicReference<Object> slot = new AtomicReference<>();
        TelemetryRegistry.setAtomicReference(slot, box); // this thread's store is the only one
        CountDownLatch refused = new CountDownLatch(1);
        AtomicReference<Boolean> swapped = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            swapped.set(TelemetryRegistry.compareAndSetAtomicReference(slot, new Object(), box));
            refused.countDown(); // unwoven, so it orders nothing as far as the model knows
        });
        Thread reader = new Thread(() -> {
            try {
                refused.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Box got = (Box) TelemetryRegistry.getAtomicReference(slot);
            detector.recordFieldRead(got, "value");
            got.value++;
            detector.recordFieldWrite(got, "value");
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertEquals(Boolean.FALSE, swapped.get(), "the slot never held what the writer expected");
        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "The writer wrote the box and its swap failed, so the box the reader got is the "
                        + "one this thread stored before that write: nothing orders the write "
                        + "before the read. Announcing the swap must not have published it (#742).");
    }

    @Test
    @DisplayName("a get does not wait on a swap that never ends, and takes nothing from it")
    void anUnfinishedSwapIsWaitedForBrieflyAndPublishesNothing() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        AtomicReference<Object> slot = new AtomicReference<>();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch read = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            // A swap whose thread stalls between the store and the release: announced, stored
            // behind the hooks' back, and never released or ended while the reader looks.
            HappensBefore.swapping(slot, "value", box);
            slot.set(box);
            stored.countDown();
            try {
                read.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            HappensBefore.swapEnded();
        });
        long[] waitedNanos = new long[1];
        Thread reader = new Thread(() -> {
            try {
                stored.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long before = System.nanoTime();
            Box got = (Box) TelemetryRegistry.getAtomicReference(slot);
            waitedNanos[0] = System.nanoTime() - before;
            detector.recordFieldRead(got, "value");
            got.value++;
            detector.recordFieldWrite(got, "value");
            read.countDown();
        });
        writer.start();
        reader.start();
        reader.join(TimeUnit.SECONDS.toMillis(30));
        boolean returned = !reader.isAlive();
        read.countDown();
        writer.join();
        reader.join();
        assertTrue(returned, "the get must return although the swap it waited for never ended");
        assertTrue(waitedNanos[0] < TimeUnit.SECONDS.toNanos(5),
                "the wait is bounded at a millisecond; this get took " + waitedNanos[0] + " ns");
        assertTrue(detector.analyzeRaceConditions().hasIssues(),
                "No release of the box ever landed, so the reader took nothing: an announcement "
                        + "is not a release, and waiting for one orders nothing.");
    }

    /**
     * A reader that reads the box, loses its processor while five other stores go through the
     * slot, and only then looks for the release of what it read. {@code before} says whether it
     * noted what the slot had released before its read. {@return whether its use was reported}
     */
    private static boolean lateLookupReported(boolean before) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        AtomicReference<Object> slot = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            TelemetryRegistry.setAtomicReference(slot, box);
        });
        writer.start();
        writer.join();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch movedOn = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            // The halves of the get hook, with the reader's lost processor in between.
            Object released = before ? HappensBefore.releasedSoFar(slot, "value") : null;
            Box got = (Box) slot.get();
            read.countDown();
            try {
                movedOn.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            HappensBefore.acquireVolatileReference(slot, "value", got, released);
            detector.recordFieldRead(got, "value");
            got.value++;
            detector.recordFieldWrite(got, "value");
        });
        reader.start();
        read.await();
        for (int i = 0; i < 5; i++) {
            TelemetryRegistry.setAtomicReference(slot, new Object());
        }
        movedOn.countDown();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a reader that looks for its value's release five stores late still finds it")
    void aReleaseThatLandedBeforeTheReadIsNotLostToLaterStores() throws InterruptedException {
        assertTrue(lateLookupReported(false),
                "the case is only a test of the fix if a lookup after the read alone misses: the "
                        + "slot keeps its last four releases, and five followed the box's");
        assertFalse(lateLookupReported(true),
                "The writer wrote the box and set it, and the reader's read returned it: ordered. "
                        + "Five later stores pushed that release out of the slot's last four "
                        + "before the reader looked, but it had landed before the read, and what "
                        + "had landed then is what the hook hands over.");
    }

    /**
     * A box stored, ten other stores after it, and only then a lookup for the box's release, by a
     * reader that noted nothing before its read. {@code swapped} says whether the box went in by
     * a compare-and-set or a plain set. {@return whether the reader's use was reported}
     */
    private static boolean tenStoresLateReported(boolean swapped) throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Box box = new Box();
        AtomicReference<Object> slot = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            box.value = 1;
            detector.recordFieldWrite(box, "value");
            if (swapped) {
                TelemetryRegistry.compareAndSetAtomicReference(slot, null, box);
            } else {
                TelemetryRegistry.setAtomicReference(slot, box);
            }
        });
        writer.start();
        writer.join();
        for (int i = 0; i < 10; i++) {
            TelemetryRegistry.setAtomicReference(slot, new Object());
        }
        Thread reader = new Thread(() -> {
            // What a get that returned the box before those stores does once it runs again.
            HappensBefore.acquireVolatileReference(slot, "value", box);
            detector.recordFieldRead(box, "value");
            box.value++;
            detector.recordFieldWrite(box, "value");
        });
        reader.start();
        reader.join();
        return detector.analyzeRaceConditions().hasIssues();
    }

    @Test
    @DisplayName("a slot that has been swapped keeps enough releases for a reader ten stores late")
    void aSwappedSlotKeepsMoreReleases() throws InterruptedException {
        assertTrue(tenStoresLateReported(false),
                "a slot nobody swaps keeps its last four releases, the limit #813 documents, so "
                        + "ten stores later the box's is gone and the read takes nothing");
        assertFalse(tenStoresLateReported(true),
                "A swap is released after its store, so on a busy slot completed swaps pile up "
                        + "between a read and its lookup; a slot that has been swapped keeps "
                        + "sixteen releases, and the box's is still among them ten stores later.");
    }
}
