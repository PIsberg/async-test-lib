package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordering model on its own: which pairs of stamped accesses it calls ordered.
 *
 * <p>Every edge here is made by hand, on real threads, because that is the only way to put two
 * stamps on two threads; the agent-fed sources are pinned where the hooks are. Each positive case
 * has a twin that differs by the one call the edge comes from, since an ordering model that says
 * "ordered" too readily hides races, which is worse than having none.
 */
class HappensBeforeTest {

    /** One stamped access, as a detector would hold it. */
    private record Recorded(long orderThread, boolean orderWrite,
                            HappensBefore.@Nullable Stamp orderStamp)
            implements HappensBefore.Access {
    }

    private static Recorded stampHere(boolean write) {
        return new Recorded(Thread.currentThread().threadId(), write, HappensBefore.current());
    }

    private static Recorded onNewThread(Step before, boolean write,
                                        Step after) throws InterruptedException {
        AtomicReference<Recorded> seen = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            before.run();
            seen.set(stampHere(write));
            after.run();
        });
        thread.start();
        thread.join();
        return seen.get();
    }

    @FunctionalInterface
    private interface Step {
        void run();
    }

    private static boolean ordered(Recorded earlier, Recorded later) {
        return HappensBefore.ordered(earlier.orderThread(), earlier.orderStamp(),
                later.orderThread(), later.orderStamp());
    }

    @Test
    @DisplayName("an access before a release is ordered before an access after the matching acquire")
    void releaseThenAcquireOrders() throws InterruptedException {
        Object box = new Object();
        Recorded writer = onNewThread(() -> { }, true, () -> HappensBefore.release(box));
        Recorded reader = onNewThread(() -> HappensBefore.acquire(box), false, () -> { });

        assertTrue(ordered(writer, reader),
                "the reader acquired what the writer released after its write");
    }

    @Test
    @DisplayName("without the acquire the same two accesses are not ordered")
    void noAcquireNoOrder() throws InterruptedException {
        Object box = new Object();
        Recorded writer = onNewThread(() -> { }, true, () -> HappensBefore.release(box));
        Recorded reader = onNewThread(() -> { }, false, () -> { });

        assertFalse(ordered(writer, reader),
                "a release nobody acquired orders nothing; the two threads merely ran in turn");
    }

    @Test
    @DisplayName("an access after the release is not published by it")
    void accessAfterReleaseIsNotOrdered() throws InterruptedException {
        Object box = new Object();
        Recorded late = onNewThread(() -> HappensBefore.release(box), true, () -> { });
        Recorded reader = onNewThread(() -> HappensBefore.acquire(box), false, () -> { });

        assertFalse(ordered(late, reader),
                "the write came after the release, so the acquire says nothing about it");
    }

    @Test
    @DisplayName("releasing through one object orders nothing for an acquire of another")
    void differentObjectsDoNotOrder() throws InterruptedException {
        Object box = new Object();
        Object other = new Object();
        Recorded writer = onNewThread(() -> { }, true, () -> HappensBefore.release(box));
        Recorded reader = onNewThread(() -> HappensBefore.acquire(other), false, () -> { });

        assertFalse(ordered(writer, reader), "the model compares synchronization objects by identity");
    }

    @Test
    @DisplayName("a volatile field's release reaches a read of that field and of no other (#742)")
    void aVolatileClockIsPerField() throws InterruptedException {
        Object holder = new Object();
        Recorded writer = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "com.example.Holder.ready"));
        Recorded sameField = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "ready"), false, () -> { });
        Recorded otherField = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "com.example.Holder.other"), false, () -> { });
        Recorded wholeObject = onNewThread(() -> HappensBefore.acquire(holder), false, () -> { });

        assertTrue(ordered(writer, sameField),
                "the same field by its simple name: the qualifier depends on the call site");
        assertFalse(ordered(writer, otherField), "another volatile field published nothing");
        assertFalse(ordered(writer, wholeObject), "nor does the object's own hand-off clock");
    }

    /**
     * #813 item 2, pinned: when two writes store the same value, a read of it takes the later
     * release. A release is recorded before its store, so in the instructions between a writer's
     * release and its store a read of the earlier writer's equal value is ordered after the later
     * writer too. Nothing in the record tells the two apart, and a hook after the store would only
     * narrow the window, since the read's own hook runs after its load. Known over-ordering.
     */
    @Test
    @DisplayName("two releases of one value: a read of it is ordered after the later one (#813 item 2, known)")
    void aValueStoredTwiceIsAcquiredFromTheLaterRelease() throws InterruptedException {
        Object holder = new Object();
        Recorded first = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.flag", 1L));
        Recorded second = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.flag", 1L));
        Recorded reader = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.flag", 1L), false, () -> { });

        assertTrue(ordered(second, reader), "the later release of the value is the one acquired");
        assertTrue(ordered(first, reader), "and through it the earlier one");
    }

    @Test
    @DisplayName("a subclass field that shadows its superclass's has its own clock (#813 item 5)")
    void twoFieldsOfOneObjectSharingASimpleNameHaveTwoClocks() throws InterruptedException {
        Object holder = new Object();
        Recorded writer = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "com.example.Sub.ready"));
        Recorded shadowed = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "com.example.Base.ready"), false, () -> { });
        Recorded sameDeclaringClass = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Sub.ready"), false, () -> { });

        assertFalse(ordered(writer, shadowed),
                "Base.ready is another field; the agent names a field by its declaring class");
        assertTrue(ordered(writer, sameDeclaringClass),
                "a qualifier that is a suffix of the other names the same field");
    }

    @Test
    @DisplayName("a volatile read receives the write whose value it returned, and no later one (#742)")
    void aVolatileReadIsMatchedByTheValueItReturned() throws InterruptedException {
        Object holder = new Object();
        Recorded first = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.state", 1L));
        Recorded second = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.state", 2L));
        Recorded sawSecond = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 2L), false, () -> { });
        Recorded sawFirst = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 1L), false, () -> { });
        Recorded sawNeither = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 0L), false, () -> { });

        assertTrue(ordered(second, sawSecond), "the read returned what the second write stored");
        assertTrue(ordered(first, sawSecond),
                "and a volatile read synchronizes with every earlier write of the field too");
        assertTrue(ordered(first, sawFirst),
                "the first value, which a read returns while the second writer has released and "
                        + "not yet stored, receives the first write");
        assertFalse(ordered(second, sawFirst), "but not the second, which that read never saw");
        assertFalse(ordered(first, sawNeither) || ordered(second, sawNeither),
                "a value neither write stored, the field's initial one, receives nothing");
    }

    @Test
    @DisplayName("a read behind three pending writers takes the write it saw; behind four, the limit (#813)")
    void aReadLooksBackOverTheLastFourReleases() throws InterruptedException {
        Object holder = new Object();
        Recorded stored = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.state", 1L));
        for (long pending = 2L; pending <= 4L; pending++) {
            long value = pending;
            onNewThread(() -> { }, false,
                    () -> HappensBefore.releaseVolatile(holder, "Holder.state", value));
        }
        Recorded behindThree = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 1L), false, () -> { });
        onNewThread(() -> { }, false,
                () -> HappensBefore.releaseVolatile(holder, "Holder.state", 5L));
        Recorded behindFour = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 1L), false, () -> { });

        assertTrue(ordered(stored, behindThree),
                "three later writers released and none stored, so the field still holds 1 and the "
                        + "read synchronizes with the write that stored it");
        assertFalse(ordered(stored, behindFour),
                "the documented limit: the field keeps its last four releases, and with four "
                        + "writers in the window at once the one that stored 1 is gone, so the read "
                        + "acquires nothing, which can only add a finding");
    }

    @Test
    @DisplayName("a reference read takes the release of that object only, not of one sharing its hash (#813)")
    void aReferenceIsMatchedByIdentity() throws InterruptedException {
        Object holder = new Object();
        Object stored = new Object();
        Object other = new Object();
        long sharedHash = System.identityHashCode(stored);
        Recorded writer = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatileReference(holder, "Holder.ref", stored));
        Recorded sawStored = onNewThread(
                () -> HappensBefore.acquireVolatileReference(holder, "Holder.ref", stored), false,
                () -> { });
        Recorded sawOther = onNewThread(
                () -> HappensBefore.acquireVolatileReference(holder, "Holder.ref", other), false,
                () -> { });
        Recorded byHashOnly = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.ref", sharedHash), false,
                () -> { });

        assertTrue(ordered(writer, sawStored), "the read returned the object the write stored");
        assertFalse(ordered(writer, sawOther), "another object, whatever its hash, was not stored");
        assertTrue(ordered(writer, byHashOnly),
                "a caller that has only the hash keeps the hash comparison it had");
    }

    @Test
    @DisplayName("a release that did not say what it stored matches any value read")
    void aReleaseWithoutAValueMatchesEveryRead() throws InterruptedException {
        Object holder = new Object();
        Recorded writer = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.state"));
        Recorded reader = onNewThread(
                () -> HappensBefore.acquireVolatile(holder, "Holder.state", 42L), false, () -> { });

        assertTrue(ordered(writer, reader),
                "a caller recording by hand releases without a value, and keeps the answer it had");
    }

    @Test
    @DisplayName("a volatile read stays acquired however many volatile fields the thread reads next (#805)")
    void anAcquiredReadIsNeverForgotten() throws InterruptedException {
        Object holder = new Object();
        Recorded writer = onNewThread(() -> { }, true,
                () -> HappensBefore.releaseVolatile(holder, "Holder.ready", 1L));
        Recorded reader = onNewThread(() -> {
            HappensBefore.acquireVolatile(holder, "Holder.ready", 1L);
            // More fields than the thread keeps clocks for at hand, so the first one is evicted.
            for (int i = 0; i <= HappensBefore.FIELD_CLOCK_CACHE; i++) {
                HappensBefore.acquireVolatile(new Object(), "Holder.ready", 1L);
            }
        }, false, () -> { });
        Recorded readAgain = onNewThread(() -> {
            HappensBefore.acquireVolatile(holder, "Holder.ready", 0L); // the older value: nothing
            for (int i = 0; i <= HappensBefore.FIELD_CLOCK_CACHE; i++) {
                HappensBefore.acquireVolatile(new Object(), "Holder.ready", 1L);
            }
            HappensBefore.acquireVolatile(holder, "Holder.ready", 1L);
        }, false, () -> { });

        assertTrue(ordered(writer, reader),
                "the acquire went into the thread's clock at the read; later reads take nothing away");
        assertTrue(ordered(writer, readAgain),
                "a field whose clock fell out of the thread's cache is found again at its next read");
    }

    @Test
    @DisplayName("a withdrawn release orders nothing, and leaves another thread's release in place (#742)")
    void aRetractedReleaseOrdersNothing() throws InterruptedException {
        Object box = new Object();
        Recorded kept = onNewThread(() -> { }, true, () -> HappensBefore.release(box));
        Recorded refused = onNewThread(() -> { }, true, () -> {
            HappensBefore.release(box);
            HappensBefore.retract(box); // the offer the release was made for was refused
        });
        Recorded reader = onNewThread(() -> HappensBefore.acquire(box), false, () -> { });

        assertFalse(ordered(refused, reader), "the hand-off never happened, so it orders nothing");
        assertTrue(ordered(kept, reader), "the earlier, accepted hand-off still orders its writer");
    }

    @Test
    @DisplayName("a release the thread has stamped an access after can no longer be withdrawn")
    void aReleaseFollowedByAnAccessStays() throws InterruptedException {
        Object box = new Object();
        Recorded writer = onNewThread(() -> { }, true, () -> {
            HappensBefore.release(box);
            HappensBefore.current();
            HappensBefore.retract(box);
        });
        Recorded reader = onNewThread(() -> HappensBefore.acquire(box), false, () -> { });

        assertTrue(ordered(writer, reader),
                "a withdrawal is only for the refused call that immediately follows its release");
    }

    @Test
    @DisplayName("a fork orders the parent's earlier accesses before the child's, and only those")
    void forkOrdersWhatCameBefore() throws InterruptedException {
        Recorded beforeFork = stampHere(true);
        AtomicReference<Recorded> child = new AtomicReference<>();
        Thread thread = new Thread(() -> child.set(stampHere(false)));
        HappensBefore.fork(thread);
        Recorded afterFork = stampHere(true);
        thread.start();
        thread.join();

        assertTrue(ordered(beforeFork, child.get()), "Thread.start publishes what came before it");
        assertFalse(ordered(afterFork, child.get()),
                "the parent's write after the fork races the child; ordering it would hide that");
    }

    @Test
    @DisplayName("an unforked child is not ordered after its parent")
    void noForkNoOrder() throws InterruptedException {
        Recorded parent = stampHere(true);
        Recorded child = onNewThread(() -> { }, false, () -> { });

        assertFalse(ordered(parent, child), "without the fork the model knows nothing of the start");
    }

    @Test
    @DisplayName("a join orders everything the finished child did before the parent's later accesses")
    void joinOrdersTheChild() throws InterruptedException {
        AtomicReference<Recorded> child = new AtomicReference<>();
        Thread thread = new Thread(() -> child.set(stampHere(true)));
        thread.start();
        thread.join();
        HappensBefore.join(thread);
        Recorded parent = stampHere(false);

        assertTrue(ordered(child.get(), parent), "a returned join publishes the whole child");
    }

    @Test
    @DisplayName("joining a thread that is still running orders nothing")
    void joinOfALiveThreadIsIgnored() throws InterruptedException {
        AtomicReference<Recorded> child = new AtomicReference<>();
        java.util.concurrent.CountDownLatch stamped = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            child.set(stampHere(true));
            stamped.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        stamped.await();
        HappensBefore.join(thread);
        Recorded parent = stampHere(false);
        release.countDown();
        thread.join();

        assertFalse(ordered(child.get(), parent),
                "the child was alive, so its accesses after this point would have read as ordered too");
    }

    @Test
    @DisplayName("a stamp that does not know its own thread orders nothing")
    void foreignStampsAreNotEvidence() {
        Recorded here = stampHere(true);
        // What a record on another thread's behalf carries: the recorder's clock, not the
        // accessing thread's. It must not order the attributed thread's access.
        Recorded attributed = new Recorded(here.orderThread() + 1_000_000L, true, here.orderStamp());

        assertFalse(ordered(attributed, here));
        assertFalse(HappensBefore.ordered(1L, null, 2L, null), "no stamps, no order");
        assertTrue(HappensBefore.ordered(7L, null, 7L, null), "one thread is ordered by program order");
    }

    @Test
    @DisplayName("every conflicting pair ordered: a hand-off chain passes, one unordered write fails")
    void everyConflictOrderedFindsTheUnorderedPair() throws InterruptedException {
        Object box = new Object();
        Recorded first = onNewThread(() -> { }, true, () -> HappensBefore.release(box));
        Recorded second = onNewThread(() -> HappensBefore.acquire(box), false,
                () -> HappensBefore.release(box));
        Recorded third = onNewThread(() -> HappensBefore.acquire(box), true, () -> { });
        Recorded stray = onNewThread(() -> { }, false, () -> { });

        assertTrue(HappensBefore.everyConflictOrdered(List.of(first, second, third), true),
                "each access acquired what the previous one released");
        List<Recorded> withStray = new ArrayList<>(List.of(first, second, stray, third));
        assertFalse(HappensBefore.everyConflictOrdered(withStray, true),
                "the stray read acquired nothing and the write after it did not either");
        assertTrue(HappensBefore.everyConflictOrdered(withStray, false),
                "when reads do not conflict, as on a volatile field, only the writes are judged");
        Recorded strayWrite = onNewThread(() -> { }, true, () -> { });
        assertFalse(HappensBefore.everyConflictOrdered(List.of(first, strayWrite), false),
                "two unordered writes conflict even where reads do not");
    }

    @Test
    @DisplayName("a clock past the cap drops other threads, never its own")
    void theCapKeepsTheOwnEntry() {
        long own = 5L;
        HappensBefore.Stamp mine = HappensBefore.Stamp.of(own);
        for (long other = 1_000L; other < 1_000L + HappensBefore.MAX_ENTRIES + 10; other++) {
            mine = mine.join(HappensBefore.Stamp.of(other), own);
        }
        assertEquals(HappensBefore.MAX_ENTRIES, mine.size(), "the clock is bounded");
        assertEquals(1, mine.countOf(own), "the owner's entry survived although its id is the lowest");
        assertEquals(0, mine.countOf(1_000L), "the lowest foreign id went first");
    }

    @Test
    @DisplayName("a clock keeps the hand-offs of a run that meets a few hundred threads (#840)")
    void aFewHundredThreadsStayInTheClock() {
        long own = 5L;
        long handedOverBy = 6L;
        HappensBefore.Stamp mine = HappensBefore.Stamp.of(own).join(HappensBefore.Stamp.of(handedOverBy), own);
        for (long other = 1_000L; other < 1_300L; other++) {
            mine = mine.join(HappensBefore.Stamp.of(other), own);
        }
        assertEquals(1, mine.countOf(handedOverBy),
                "300 more threads used to push the thread that handed this one an instance out of "
                        + "its clock, and the hand-off with it");
    }

    @Test
    @DisplayName("ticking a large clock allocates the same as ticking a small one (#840)")
    void aTickCostsTheSameWhateverTheClockSize() {
        se.deversity.asynctest.AllocationBudgets.assumeMeasurable();
        long own = 5L;
        HappensBefore.Stamp mine = HappensBefore.Stamp.of(own);
        for (long other = 1_000L; other < 3_000L; other++) {
            mine = mine.join(HappensBefore.Stamp.of(other), own);
        }
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long me = Thread.currentThread().threadId();
        HappensBefore.Stamp ticked = mine;
        for (int i = 0; i < 1_000; i++) { // warm up, so the measurement sees compiled code
            ticked = ticked.tick(own);
        }
        long before = threads.getThreadAllocatedBytes(me);
        for (int i = 0; i < 1_000; i++) {
            ticked = ticked.tick(own);
        }
        long bytes = threads.getThreadAllocatedBytes(me) - before;

        assertEquals(2_001, ticked.countOf(own), "every tick advanced the own entry");
        assertEquals(1, ticked.countOf(2_999L), "and kept the others");
        assertTrue(bytes < 1_000L * 128,
                "1,000 ticks of a clock that knows 2,001 threads allocated " + bytes + " bytes: a "
                        + "tick copies the whole clock, which a run meeting many threads pays on "
                        + "every release");
    }

    @Test
    @DisplayName("only containers whose contract publishes their elements are edges")
    void publishingContainers() {
        assertTrue(HappensBefore.publishesElements(new java.util.concurrent.LinkedBlockingQueue<>()));
        assertTrue(HappensBefore.publishesElements(new java.util.concurrent.ConcurrentHashMap<>()));
        assertTrue(HappensBefore.publishesElements(new java.util.concurrent.atomic.AtomicReference<>()));
        assertTrue(HappensBefore.publishesElements(
                java.util.Collections.synchronizedMap(new java.util.HashMap<>())));
        assertFalse(HappensBefore.publishesElements(new java.util.ArrayDeque<>()),
                "an ArrayDeque promises nothing about the threads that use it");
        assertFalse(HappensBefore.publishesElements(new java.util.HashMap<>()));
        assertFalse(HappensBefore.publishesElements(null));
    }
}
