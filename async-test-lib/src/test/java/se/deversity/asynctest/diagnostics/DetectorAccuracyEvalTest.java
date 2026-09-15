package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestContext;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

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
     * then joins both, so every recording genuinely happens from distinct live threads.
     * A worker that fails fails the calling test: a swallowed failure removes that worker's
     * recordings, and what is left is a detector asserted against half its input (#413).
     * Captured inside the runnable, not via setUncaughtExceptionHandler, because the
     * uncaught-handler pair below asserts on exactly which handler a worker carries. */
    private static void onTwoThreads(Runnable first, Runnable second) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> died =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable sync1 = () -> { await(barrier); first.run(); };
        Runnable sync2 = () -> { await(barrier); second.run(); };
        Thread t1 = new Thread(capturing(sync1, died));
        Thread t2 = new Thread(capturing(sync2, died));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        if (died.get() != null) {
            throw new AssertionError(
                    "a worker thread failed instead of completing its recordings, so the "
                            + "detector under test saw only part of its input", died.get());
        }
    }

    /** {@return {@code work}, with any failure parked in {@code died} for the join to rethrow} */
    private static Runnable capturing(Runnable work,
            java.util.concurrent.atomic.AtomicReference<Throwable> died) {
        return () -> {
            try {
                work.run();
            } catch (Throwable failure) {
                died.compareAndSet(null, failure);
            }
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The harness's own contract, pinned because #413 was this harness and not a detector.
     *
     * <p>Before the fix, a worker's uncaught exception vanished: {@code join} returned, the
     * test asserted against a detector that had seen half its input, and the only symptom was
     * an assertion message with the evidence already gone.
     */
    @Test
    @DisplayName("harness: a worker that dies fails the test instead of vanishing (#413)")
    void onTwoThreadsPropagatesAWorkerDeath() {
        AssertionError propagated = org.junit.jupiter.api.Assertions.assertThrows(
                AssertionError.class,
                () -> onTwoThreads(
                        () -> { throw new IllegalStateException("worker died"); },
                        () -> { }));
        assertTrue(propagated.getCause() instanceof IllegalStateException,
                "the worker's own failure must ride along as the cause, or the next "
                        + "occurrence is again diagnosed from nothing");
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
                "PINNED FALSE POSITIVE: the increments are fully lock-protected and the code is "
                        + "correct, but nothing told the library about this lock. It is neither "
                        + "the shared instance's own monitor, which holdsLock can answer for, nor "
                        + "a lock declared through AsyncTestContext.holdingLock, and a plain "
                        + "synchronized block on a third object emits no callback anyone can "
                        + "observe. The next test is the same lock, declared, and it is silent. "
                        + "If this one goes silent too, the library found a way to see undeclared "
                        + "monitors - flip the assertion and update detector-accuracy-eval.md");
    }

    @Test
    @DisplayName("race: the same external lock, declared, is recognised and stays silent")
    void raceDetectorIsSilentWhenTheExternalLockIsDeclared() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Counter shared = new Counter();
        ReentrantLock lock = new ReentrantLock();
        Runnable increment = () -> {
            try (var held = AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    detector.recordFieldRead(shared, "value");
                    shared.value++;
                    detector.recordFieldWrite(shared, "value");
                } finally {
                    lock.unlock();
                }
            }
        };
        onTwoThreads(increment, increment);

        assertFalse(detector.analyze().hasIssues(),
                "The increments are lock-protected exactly as in the test above; the only "
                        + "difference is that this lock was declared, so the detector can see "
                        + "that one lock covered every access. Reporting here would be reporting "
                        + "the fix. If this fires, the fingerprint is not reaching the record "
                        + "path - check that recordFieldRead/Write still call "
                        + "HeldLocks.lockFingerprint(object) at record time rather than later");
    }

    @Test
    @DisplayName("race: two threads on different declared locks is still a race")
    void raceDetectorFiresWhenThreadsDeclareDifferentLocks() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Counter shared = new Counter();
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        AtomicBoolean useFirst = new AtomicBoolean(true);
        Runnable increment = () -> {
            ReentrantLock mine = useFirst.getAndSet(false) ? first : second;
            try (var held = AsyncTestContext.holdingLock(mine)) {
                mine.lock();
                try {
                    detector.recordFieldRead(shared, "value");
                    shared.value++;
                    detector.recordFieldWrite(shared, "value");
                } finally {
                    mine.unlock();
                }
            }
        };
        onTwoThreads(increment, increment);

        assertTrue(detector.analyze().hasIssues(),
                "Each thread held a lock and they were different locks, so neither excluded the "
                        + "other and the lost update is exactly as available as with no locks. A "
                        + "model that only asked 'was something held' would call this guarded, "
                        + "which is why the comparison is between the sets and not their emptiness");
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
    @DisplayName("atomicity: the externally-locked twin fires identically (pinned false positive)")
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
                "PINNED FALSE POSITIVE: the read-modify-write is atomic under the lock, but "
                        + "nothing here lets the validator know that. The guard is a private "
                        + "lock object rather than the owner's monitor, and this call site uses "
                        + "the overload that names no owner at all - which is also what the "
                        + "agent-fed path uses, since weaving captures qualified field names but "
                        + "no object reference. recordFieldAccessOn closes the owner's-monitor "
                        + "case (see the two tests below); an external lock stays invisible. If "
                        + "this went silent, flip the assertion and update "
                        + "detector-accuracy-eval.md");
    }

    @Test
    @DisplayName("atomicity: a declared external lock is recognised (the pinned FP above, closed)")
    void atomicityValidatorIsSilentWhenOneDeclaredLockGuardsEveryAccess()
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        ReentrantLock lock = new ReentrantLock();
        Runnable readModifyWrite = () -> {
            try (var held = AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    validator.recordFieldAccessOn(shared, "balance", shared.value, false);
                    shared.value++;
                    validator.recordFieldAccessOn(shared, "balance", shared.value, true);
                } finally {
                    lock.unlock();
                }
            }
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertFalse(validator.analyze().hasIssues(),
                "Same compound operation as the test above, and the same kind of external lock. "
                        + "The difference is that this one is declared, so it enters the field's "
                        + "lockset and the intersection across both threads is that lock. A "
                        + "read-modify-write serialised by one lock is atomic, and reporting it "
                        + "would be reporting the fix");
    }

    @Test
    @DisplayName("atomicity: two threads on different declared locks is still a race")
    void atomicityValidatorStillFiresWhenTheTwoThreadsTakeDifferentLocks()
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        AtomicBoolean useFirst = new AtomicBoolean(true);
        Runnable readModifyWrite = () -> {
            // An explicit toggle, not the thread name: default names are "Thread-N" with N
            // counting across the whole JVM, so keying on them makes the test order-dependent.
            ReentrantLock mine = useFirst.getAndSet(false) ? first : second;
            try (var held = AsyncTestContext.holdingLock(mine)) {
                mine.lock();
                try {
                    validator.recordFieldAccessOn(shared, "balance", shared.value, false);
                    shared.value++;
                    validator.recordFieldAccessOn(shared, "balance", shared.value, true);
                } finally {
                    mine.unlock();
                }
            }
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertTrue(validator.analyze().hasIssues(),
                "Both threads held a lock, but not the same one, so neither excludes the other "
                        + "and the read-modify-write is exactly as broken as with no lock. The "
                        + "intersection of the two locksets is empty, which is the whole reason "
                        + "the model is an intersection rather than a per-access boolean");
    }

    @Test
    @DisplayName("atomicity: owner-aware recording, guarded on the owner's monitor, is silent")
    void atomicityValidatorIsSilentWhenTheOwnersOwnMonitorGuardedEveryAccess()
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        Runnable readModifyWrite = () -> {
            synchronized (shared) {
                validator.recordFieldAccessOn(shared, "balance", shared.value, false);
                shared.value++;
                validator.recordFieldAccessOn(shared, "balance", shared.value, true);
            }
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertFalse(validator.analyze().hasIssues(),
                "This is the same compound operation as the two tests above, guarded by the "
                        + "owner's own monitor and recorded through the overload that names the "
                        + "owner. The validator can probe that lock, so correct code must produce "
                        + "no finding - otherwise recordFieldAccessOn buys nothing and the fix "
                        + "still looks as broken as the bug");
    }

    @Test
    @DisplayName("atomicity: owner-aware recording with no lock held still fires")
    void atomicityValidatorStillFiresWhenTheOwnerIsKnownButNoLockIsHeld()
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Counter shared = new Counter();
        Runnable readModifyWrite = () -> {
            validator.recordFieldAccessOn(shared, "balance", shared.value, false);
            shared.value++;
            validator.recordFieldAccessOn(shared, "balance", shared.value, true);
        };
        onTwoThreads(readModifyWrite, readModifyWrite);

        assertTrue(validator.analyze().hasIssues(),
                "The owner is known here and no lock is held on it, which is the genuine race. "
                        + "Naming the owner must not turn the detector off - if this goes silent, "
                        + "the guard probe is answering true when no monitor is held");
    }

    // ---- AtomicityValidator, agent-path rules: #311, #312, #313 ----
    //
    // These drive the ten-argument agent overload directly, with explicit thread ids and raw
    // lock fingerprints, because the rules under test are about the order of accesses. Real
    // threads would make the recorded order nondeterministic and these assertions flaky; the
    // validator only ever sees the recorded stream, and this is the same stream the telemetry
    // drain delivers. A fingerprint nobody registered is one opaque lock: the same value twice
    // is the same lock, two values are two locks, which is all these shapes need.

    private static final long NO_LOCKS = 0L;
    private static final long WRITE_LOCK = 0x1111L;
    private static final long OTHER_LOCK = 0x2222L;

    private static void agentAccess(AtomicityValidator validator, String field, boolean write,
                                    long threadId, long fingerprint, int identity) {
        validator.recordFieldAccessUnderLocks(field, null, write, threadId, fingerprint, 0, 0,
                false, Integer.MIN_VALUE, identity);
    }

    /**
     * A write that stores a named reference, so the value evidence #326 added is exercised.
     *
     * @param validator      the validator under test
     * @param field          the field written
     * @param threadId       the writing thread
     * @param identity       identity of the object the field belongs to
     * @param storedIdentity identity of the reference stored, as the weaver would report it
     */
    private static void agentStore(AtomicityValidator validator, String field, long threadId,
                                   int identity, int storedIdentity) {
        validator.recordFieldAccessUnderLocks(field, null, true, threadId, NO_LOCKS, 0, 0,
                false, Integer.MIN_VALUE, identity, storedIdentity);
    }

    @Test
    @DisplayName("atomicity: a hint read re-read under the write lock is silent (#311)")
    void atomicityHintReadsReReadUnderTheWriteLockAreSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        // Publish the receiver first, so both threads' patterns below are post-construction.
        agentAccess(validator, "segment.resizeThreshold", false, 1, NO_LOCKS, 77);
        agentAccess(validator, "segment.resizeThreshold", false, 2, NO_LOCKS, 77);
        for (long thread = 1; thread <= 2; thread++) {
            agentAccess(validator, "segment.resizeThreshold", false, thread, NO_LOCKS, 77);
            agentAccess(validator, "segment.resizeThreshold", false, thread, WRITE_LOCK, 77);
            agentAccess(validator, "segment.resizeThreshold", true, thread, WRITE_LOCK, 77);
        }
        assertFalse(validator.analyze().hasIssues(),
                "Every write held the same lock and the unlocked read was re-established under "
                        + "that lock by the same thread in the same round before anything acted "
                        + "on it. That is the safe half of double-checked locking - spring's "
                        + "resizeThreshold hint - and reporting it reports the idiom, not a bug");
    }

    @Test
    @DisplayName("atomicity: an unlocked read never re-read under the lock still fires (#311)")
    void atomicityStillFiresWhenTheUnlockedReadIsTheOnlyRead() {
        AtomicityValidator validator = new AtomicityValidator();
        agentAccess(validator, "cache.threshold", false, 1, NO_LOCKS, 78);
        agentAccess(validator, "cache.threshold", false, 2, NO_LOCKS, 78);
        for (long thread = 1; thread <= 2; thread++) {
            agentAccess(validator, "cache.threshold", false, thread, NO_LOCKS, 78);
            agentAccess(validator, "cache.threshold", true, thread, WRITE_LOCK, 78);
        }
        assertTrue(validator.analyze().hasIssues(),
                "The unlocked read is the only read: nothing re-establishes the value under the "
                        + "lock the writes agree on, so the hint is the decision and the TOCTOU "
                        + "window is real. The #311 rule must not retract this");
    }

    @Test
    @DisplayName("atomicity: a re-read under some other lock still fires (#311)")
    void atomicityStillFiresWhenTheReReadIsUnderALockTheWritesDoNotHold() {
        AtomicityValidator validator = new AtomicityValidator();
        agentAccess(validator, "cache.limit", false, 1, NO_LOCKS, 79);
        agentAccess(validator, "cache.limit", false, 2, NO_LOCKS, 79);
        for (long thread = 1; thread <= 2; thread++) {
            agentAccess(validator, "cache.limit", false, thread, NO_LOCKS, 79);
            agentAccess(validator, "cache.limit", false, thread, OTHER_LOCK, 79);
            agentAccess(validator, "cache.limit", true, thread, WRITE_LOCK, 79);
        }
        assertTrue(validator.analyze().hasIssues(),
                "The later read holds a lock, but not one the writes hold, so it excludes no "
                        + "writer and confirms nothing. Only a re-read under a lock that covers "
                        + "the writes turns the unlocked read into a hint");
    }

    @Test
    @DisplayName("atomicity: construction writes are not raced against later readers (#312)")
    void atomicityConstructionWritesAreNotRacedAgainstLaterReaders() {
        AtomicityValidator validator = new AtomicityValidator();
        // The builder writes while no other thread can reach the receiver, under its own lock -
        // netty builds a chunk's metadata under the arena lock and serves it under the chunk's.
        // The reads keep coming in later rounds: that corroboration is what licenses the excuse,
        // and the single-round variant of this stream is pinned as still firing by
        // LocksetIntersectionTest.disjointLocksStillReport.
        validator.markInvocationStart();
        agentAccess(validator, "chunk.mask", true, 1, WRITE_LOCK, 88);
        agentAccess(validator, "chunk.mask", true, 1, WRITE_LOCK, 88);
        agentAccess(validator, "chunk.mask", false, 2, OTHER_LOCK, 88);
        agentAccess(validator, "chunk.mask", false, 3, OTHER_LOCK, 88);
        validator.markInvocationStart();
        agentAccess(validator, "chunk.mask", false, 1, OTHER_LOCK, 88);
        agentAccess(validator, "chunk.mask", false, 2, OTHER_LOCK, 88);
        assertFalse(validator.analyze().hasIssues(),
                "Both writes happened while the receiver was reachable only from the thread "
                        + "building it, and every post-publication access is a read under one "
                        + "shared lock, across rounds the harness orders. Intersecting "
                        + "construction locks against post-publication locks is how an unshared "
                        + "write turns into a finding, which is the #312 false positive");
    }

    @Test
    @DisplayName("atomicity: a writer that keeps writing after publication still fires (#312)")
    void atomicityStillFiresWhenWritesContinueAfterPublication() {
        AtomicityValidator validator = new AtomicityValidator();
        // Corroborated hand-off shape in every other respect - rounds of reads follow - but the
        // builder writes once more after the receiver escaped, and that write holds nothing.
        validator.markInvocationStart();
        agentAccess(validator, "node.next", true, 1, WRITE_LOCK, 89);
        agentAccess(validator, "node.next", false, 2, NO_LOCKS, 89);
        agentAccess(validator, "node.next", true, 1, NO_LOCKS, 89);
        validator.markInvocationStart();
        agentAccess(validator, "node.next", false, 2, NO_LOCKS, 89);
        validator.markInvocationStart();
        agentAccess(validator, "node.next", false, 2, NO_LOCKS, 89);
        assertTrue(validator.analyze().hasIssues(),
                "The receiver escaped - another thread has read it - and the builder wrote again "
                        + "with no lock. Excusing that would excuse every race that starts one "
                        + "access after publication; the exclusive phase must end permanently at "
                        + "the first foreign access");
    }

    // ---- AtomicityValidator, ownership transfer: #555 ----
    //
    // An object taken out of a queue or an atomic slot is exclusive to the thread that took it,
    // the way a receiver under construction is exclusive to its builder. netty's adaptive
    // allocator moves a chunk between magazines that way: under one magazine's lock, then taken
    // from the shared cache or the next-in-line slot, then under another magazine's lock, or with
    // no lock at all by a thread that took it with getAndSet. The lock changes; the exclusion
    // never lapses.

    private static final long THIRD_LOCK = 0x3333L;

    @Test
    @DisplayName("atomicity: a receiver that moves between locks through a take is silent (#555)")
    void atomicityLockMigrationThroughATakeIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        migrateBetweenLocks(validator, 90, true);
        assertFalse(validator.analyze().hasIssues(),
                "Every access held a lock. The lock changed once, at a point where one thread "
                        + "took the receiver out of a queue, and every access in each ownership "
                        + "generation agrees on its lock. That is netty's chunk leaving one "
                        + "magazine and joining another; the empty intersection across the whole "
                        + "run is an artefact of intersecting across the hand-off");
    }

    @Test
    @DisplayName("atomicity: the same lock change with no take still fires (#555)")
    void atomicityLockChangeWithoutATakeStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        migrateBetweenLocks(validator, 91, false);
        assertTrue(validator.analyze().hasIssues(),
                "Identical accesses, but nothing took the receiver: the lock simply changed, and "
                        + "two locks that never intersect protect nothing against each other. "
                        + "Only an observed take may start a new ownership generation");
    }

    @Test
    @DisplayName("atomicity: unlocked use by whoever took the receiver atomically is silent (#555)")
    void atomicityUnlockedUseAfterAnAtomicTakeIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        takeAndUseUnlocked(validator, 92, true);
        assertFalse(validator.analyze().hasIssues(),
                "Each thread took the receiver with an atomic getAndSet before touching it and "
                        + "touched it with no lock. Nothing else could reach it between the take "
                        + "and the hand-back, which is netty's allocateWithoutLock path; the "
                        + "take, not a lock, is what excludes the other threads");
    }

    @Test
    @DisplayName("atomicity: an object built without a lock and then handed off by takes is silent (#555)")
    void atomicityUnlockedConstructionFollowedByTakesIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        String field = "buffer.writerIndex";
        long thread = 1;
        // netty's pooled buffer: the recycler's first user builds it with no lock, then every later
        // user takes it out of the recycler's queue before touching it. No access after the
        // builder's is anything but a taker's own.
        validator.markInvocationStart();
        agentAccess(validator, field, true, thread, NO_LOCKS, 96);
        agentAccess(validator, field, false, thread, NO_LOCKS, 96);
        for (int round = 0; round < 3; round++) {
            validator.markInvocationStart();
            for (int user = 0; user < 2; user++) {
                thread++;
                validator.recordOwnershipTaken(96, thread);
                agentAccess(validator, field, true, thread, WRITE_LOCK, 96);
                agentAccess(validator, field, true, thread, NO_LOCKS, 96);
            }
        }
        assertFalse(validator.analyze().hasIssues(),
                "The builder's accesses came before anyone else's, and the object then only ever "
                        + "moved through observed takes. The take corroborates the hand-off the "
                        + "construction phase assumed, the way later rounds do for #312");
    }

    @Test
    @DisplayName("atomicity: unlocked use with no take still fires (#555)")
    void atomicityUnlockedUseWithoutATakeStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        takeAndUseUnlocked(validator, 93, false);
        assertTrue(validator.analyze().hasIssues(),
                "The same unlocked reads and writes from several threads with no take in "
                        + "between is the plain race, and must keep reporting");
    }

    @Test
    @DisplayName("atomicity: a thread that uses a taken receiver without taking it still fires (#555)")
    void atomicityAccessByAThreadThatDidNotTakeTheReceiverStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "chunk.allocated", true, 1, WRITE_LOCK, 94);
        validator.recordOwnershipTaken(94, 2);
        agentAccess(validator, "chunk.allocated", false, 2, NO_LOCKS, 94);
        agentAccess(validator, "chunk.allocated", true, 2, NO_LOCKS, 94);
        // Thread 3 kept a reference from before the take and writes through it, unlocked.
        agentAccess(validator, "chunk.allocated", true, 3, NO_LOCKS, 94);
        agentAccess(validator, "chunk.allocated", false, 2, NO_LOCKS, 94);
        agentAccess(validator, "chunk.allocated", true, 2, NO_LOCKS, 94);
        validator.markInvocationStart();
        agentAccess(validator, "chunk.allocated", true, 3, NO_LOCKS, 94);
        agentAccess(validator, "chunk.allocated", true, 2, NO_LOCKS, 94);
        assertTrue(validator.analyze().hasIssues(),
                "Thread 2 took the receiver, but thread 3 wrote to it anyway through a reference "
                        + "it already held, with no lock. A take excludes only the threads that "
                        + "go through the slot; the first access by anyone else ends the "
                        + "exclusion exactly as it ends construction");
    }

    @Test
    @DisplayName("atomicity: locks that disagree inside one ownership generation still fire (#555)")
    void atomicityDisagreeingLocksWithinOneGenerationStillFire() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "chunk.allocated", true, 1, WRITE_LOCK, 95);
        agentAccess(validator, "chunk.allocated", false, 2, WRITE_LOCK, 95);
        validator.recordOwnershipTaken(95, 3);
        agentAccess(validator, "chunk.allocated", true, 3, OTHER_LOCK, 95);
        agentAccess(validator, "chunk.allocated", true, 4, THIRD_LOCK, 95);
        agentAccess(validator, "chunk.allocated", false, 3, OTHER_LOCK, 95);
        validator.markInvocationStart();
        agentAccess(validator, "chunk.allocated", true, 4, THIRD_LOCK, 95);
        agentAccess(validator, "chunk.allocated", true, 3, OTHER_LOCK, 95);
        assertTrue(validator.analyze().hasIssues(),
                "After the take, threads 3 and 4 both write under locks that never intersect. "
                        + "A take licenses a new lock for the new owner, not two locks at once");
    }

    @Test
    @DisplayName("atomicity: an alias that writes after the taker's last access still fires (#559)")
    void atomicityAliasWritingAfterTheTakersLastAccessStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        takeThenMaybeAliasWriteLast(validator, 97, true);
        assertTrue(validator.analyze().hasIssues(),
                "Thread 99 kept a reference from before each take and wrote through it, under a "
                        + "lock of its own, after the taker's last access in a generation no later "
                        + "take closed. In drain order the alias comes after every taker access, so "
                        + "each of those stayed marked exclusive and the alias write agreed with "
                        + "nothing but itself. The taker's accesses are only exclusive while no one "
                        + "else can reach the object, and the alias shows someone could");
    }

    @Test
    @DisplayName("atomicity: the same takes with no alias stay silent (#559)")
    void atomicityTakesWithNoAliasStaySilent() {
        AtomicityValidator validator = new AtomicityValidator();
        takeThenMaybeAliasWriteLast(validator, 98, false);
        assertFalse(validator.analyze().hasIssues(),
                "The identical takes and unlocked taker accesses, and no thread that kept a "
                        + "reference: each owner had the object to itself. Withdrawing the take's "
                        + "exclusivity must need another thread's access, not the take alone");
    }

    @Test
    @DisplayName("atomicity: an alias inside a generation a later take closed fires (#559, #630)")
    void atomicityAliasInAGenerationALaterTakeClosedFires() {
        AtomicityValidator validator = new AtomicityValidator();
        String field = "chunk.allocated";
        validator.markInvocationStart();
        agentAccess(validator, field, true, 1, WRITE_LOCK, 90);
        validator.recordOwnershipTaken(90, 2);
        agentAccess(validator, field, true, 2, NO_LOCKS, 90);
        agentAccess(validator, field, true, 3, OTHER_LOCK, 90);
        validator.recordOwnershipTaken(90, 4);
        agentAccess(validator, field, true, 4, NO_LOCKS, 90);
        validator.markInvocationStart();
        validator.recordOwnershipTaken(90, 5);
        agentAccess(validator, field, true, 5, NO_LOCKS, 90);
        assertTrue(validator.analyze().hasIssues(),
                "Thread 3 was neither generation 1's taker (thread 2) nor its previous owner (thread 1); "
                        + "its alias access withdraws exclusivity even though a later take closed the generation (#630)");
    }

    @Test
    @DisplayName("atomicity: a late-published access by the previous owner in a closed generation stays silent (#630)")
    void atomicityLateAccessByPreviousOwnerInClosedGenerationStaysSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        String field = "chunk.allocated";
        validator.markInvocationStart();
        agentAccess(validator, field, true, 1, WRITE_LOCK, 90);
        validator.recordOwnershipTaken(90, 2);
        agentAccess(validator, field, true, 2, NO_LOCKS, 90);
        // Late-published access from thread 1 (the previous owner) during handoff:
        agentAccess(validator, field, true, 1, WRITE_LOCK, 90);
        validator.recordOwnershipTaken(90, 4);
        agentAccess(validator, field, true, 4, NO_LOCKS, 90);
        validator.markInvocationStart();
        validator.recordOwnershipTaken(90, 5);
        agentAccess(validator, field, true, 5, NO_LOCKS, 90);
        assertFalse(validator.analyze().hasIssues(),
                "Thread 1 was the previous owner handing off to thread 2; in a closed generation, "
                        + "a late-published access by the previous owner does not withdraw exclusivity (#557, #630)");
    }

    /**
     * Three rounds of: a take by a new thread, that taker's unlocked read and write, and, when
     * {@code withAlias}, a write by thread 99 under its own lock after the taker's last access.
     */
    private static void takeThenMaybeAliasWriteLast(AtomicityValidator validator, int identity,
                                                    boolean withAlias) {
        String field = "chunk.allocated";
        validator.markInvocationStart();
        agentAccess(validator, field, true, 1, WRITE_LOCK, identity);
        long thread = 1;
        for (int round = 0; round < 3; round++) {
            validator.markInvocationStart();
            thread++;
            validator.recordOwnershipTaken(identity, thread);
            agentAccess(validator, field, false, thread, NO_LOCKS, identity);
            agentAccess(validator, field, true, thread, NO_LOCKS, identity);
            if (withAlias) {
                agentAccess(validator, field, true, 99, OTHER_LOCK, identity);
            }
        }
    }

    /**
     * Construction under one lock, a shared phase under a second, then a third lock after the
     * point where {@code withTake} records a take.
     */
    private static void migrateBetweenLocks(AtomicityValidator validator, int identity,
                                            boolean withTake) {
        String field = "chunk.allocated";
        validator.markInvocationStart();
        agentAccess(validator, field, true, 1, THIRD_LOCK, identity);
        for (long thread = 2; thread <= 3; thread++) {
            agentAccess(validator, field, false, thread, WRITE_LOCK, identity);
            agentAccess(validator, field, true, thread, WRITE_LOCK, identity);
        }
        validator.markInvocationStart();
        for (long thread = 2; thread <= 3; thread++) {
            agentAccess(validator, field, false, thread, WRITE_LOCK, identity);
            agentAccess(validator, field, true, thread, WRITE_LOCK, identity);
        }
        if (withTake) {
            validator.recordOwnershipTaken(identity, 4);
        }
        for (long thread = 4; thread <= 5; thread++) {
            agentAccess(validator, field, false, thread, OTHER_LOCK, identity);
            agentAccess(validator, field, true, thread, OTHER_LOCK, identity);
        }
        validator.markInvocationStart();
        for (long thread = 4; thread <= 5; thread++) {
            agentAccess(validator, field, false, thread, OTHER_LOCK, identity);
            agentAccess(validator, field, true, thread, OTHER_LOCK, identity);
        }
    }

    /** Several threads per round each take the receiver, when {@code withTake}, and use it unlocked. */
    private static void takeAndUseUnlocked(AtomicityValidator validator, int identity,
                                           boolean withTake) {
        String field = "chunk.allocated";
        long thread = 1;
        validator.markInvocationStart();
        agentAccess(validator, field, true, thread, WRITE_LOCK, identity);
        for (int round = 0; round < 3; round++) {
            if (round > 0) {
                validator.markInvocationStart();
            }
            for (int user = 0; user < 3; user++) {
                thread++;
                if (withTake) {
                    validator.recordOwnershipTaken(identity, thread);
                }
                agentAccess(validator, field, false, thread, NO_LOCKS, identity);
                agentAccess(validator, field, true, thread, NO_LOCKS, identity);
            }
        }
    }

    @Test
    @DisplayName("atomicity: the settled single-check cache is silent (#313)")
    void atomicitySettledSingleCheckCacheIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "writer.serializerCache", false, 1, NO_LOCKS, 99);
        agentAccess(validator, "writer.serializerCache", false, 2, NO_LOCKS, 99);
        agentAccess(validator, "writer.serializerCache", true, 1, NO_LOCKS, 99);
        agentAccess(validator, "writer.serializerCache", true, 2, NO_LOCKS, 99);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "writer.serializerCache", false, 1, NO_LOCKS, 99);
            agentAccess(validator, "writer.serializerCache", false, 2, NO_LOCKS, 99);
        }
        assertFalse(validator.analyze().hasIssues(),
                "Both threads missed, both filled, and one write was lost - then the cache "
                        + "settled: two later rounds of reads from both threads and not another "
                        + "write. That convergence is jackson's racy single-check idiom doing "
                        + "what it is designed to do, and the lost update cost a recomputation");
    }

    @Test
    @DisplayName("atomicity: lost updates that keep writing every round still fire (#313)")
    void atomicityStillFiresWhenWritesNeverSettle() {
        AtomicityValidator validator = new AtomicityValidator();
        for (int round = 0; round < 3; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "counter.value", false, 1, NO_LOCKS, 98);
            agentAccess(validator, "counter.value", false, 2, NO_LOCKS, 98);
            agentAccess(validator, "counter.value", true, 1, NO_LOCKS, 98);
            agentAccess(validator, "counter.value", true, 2, NO_LOCKS, 98);
        }
        assertTrue(validator.analyze().hasIssues(),
                "A read-modify-write that races in every round is a lost update, not a cache: "
                        + "nothing converges. The #313 rule keys on settling, so this must stay "
                        + "as loud as it ever was");
    }

    @Test
    @DisplayName("atomicity: writes spread over more rounds than writers are not warming (#313)")
    void atomicityStillFiresWhenWarmingOutlastsTheWriters() {
        AtomicityValidator validator = new AtomicityValidator();
        for (int round = 0; round < 3; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "phase.counter", false, 1, NO_LOCKS, 95);
            agentAccess(validator, "phase.counter", false, 2, NO_LOCKS, 95);
            agentAccess(validator, "phase.counter", true, 1, NO_LOCKS, 95);
            agentAccess(validator, "phase.counter", true, 2, NO_LOCKS, 95);
        }
        for (int round = 0; round < 3; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "phase.counter", false, 1, NO_LOCKS, 95);
            agentAccess(validator, "phase.counter", false, 2, NO_LOCKS, 95);
        }
        assertTrue(validator.analyze().hasIssues(),
                "Two writers cannot take three rounds to warm a single-check cache: each extra "
                        + "warm round exists because a loser re-missed, and there are only so "
                        + "many losers. A counter that stops being written mid-run must not "
                        + "out-settle its own warming");
    }

    @Test
    @DisplayName("atomicity: a one-shot view field settles by its receiver staying in service (#313)")
    void atomicityOneShotViewFieldSettlesByReceiverActivity() {
        AtomicityValidator validator = new AtomicityValidator();
        // Jackson's PrivateMaxEntriesMap.entrySet: two threads race the lazy view creation once,
        // the field is never touched again, and the map itself stays hot for the rest of the run.
        validator.markInvocationStart();
        agentAccess(validator, "map.entrySetView", false, 1, NO_LOCKS, 94);
        agentAccess(validator, "map.entrySetView", false, 2, NO_LOCKS, 94);
        agentAccess(validator, "map.entrySetView", true, 1, NO_LOCKS, 94);
        agentAccess(validator, "map.entrySetView", true, 2, NO_LOCKS, 94);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "map.size", false, 1, NO_LOCKS, 94);
            agentAccess(validator, "map.size", false, 2, NO_LOCKS, 94);
        }
        assertFalse(validator.analyze().hasIssues(),
                "Both threads missed the view check and both created it: a lost write costs one "
                        + "extra view object and nothing else. The field cannot show settled "
                        + "reads because nothing reads it again, but the run kept executing for "
                        + "two more rounds with the field never raced again, which is the same "
                        + "convergence the settled-cache rule accepts on the run's own clock");
    }

    @Test
    @DisplayName("atomicity: a blind store is initialization, not a single-check cache (#313)")
    void atomicityStillFiresWhenTheWarmRoundStoreWasBlind() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "config.instance", false, 1, NO_LOCKS, 97);
        agentAccess(validator, "config.instance", true, 2, NO_LOCKS, 97);
        agentAccess(validator, "config.instance", false, 1, NO_LOCKS, 97);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "config.instance", false, 1, NO_LOCKS, 97);
            agentAccess(validator, "config.instance", false, 2, NO_LOCKS, 97);
        }
        assertTrue(validator.analyze().hasIssues(),
                "The store did not depend on a miss check - the writer never read the field - so "
                        + "this is racy initialization, not the single-check idiom, however "
                        + "quietly it settles afterwards");
    }

    @Test
    @DisplayName("atomicity: a run too short to show convergence keeps its finding (#313)")
    void atomicityDoesNotSettleWithoutTwoQuietRounds() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "lazy.holder", false, 1, NO_LOCKS, 96);
        agentAccess(validator, "lazy.holder", false, 2, NO_LOCKS, 96);
        agentAccess(validator, "lazy.holder", true, 1, NO_LOCKS, 96);
        agentAccess(validator, "lazy.holder", true, 2, NO_LOCKS, 96);
        validator.markInvocationStart();
        agentAccess(validator, "lazy.holder", false, 1, NO_LOCKS, 96);
        agentAccess(validator, "lazy.holder", false, 2, NO_LOCKS, 96);
        assertTrue(validator.analyze().hasIssues(),
                "One quiet round is not convergence, it is a short run. Silence here must be "
                        + "earned by evidence the field settled, so the default stays a finding");
    }


    /**
     * A view cache stores a value that then goes quiet, and stays excused (#326).
     *
     * <p>The silent half of the pair. Two threads miss the check and both create a view; one
     * store is lost, costing one extra object. Neither published view is written again, which is
     * what an effectively immutable value looks like in the access stream, so the settle excuse
     * that #313 established still applies with the value evidence in hand.
     */
    @Test
    @DisplayName("atomicity: a settled cache whose stored value goes quiet stays silent (#326)")
    void atomicitySettledCacheWithQuiescentValueIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "holder.view", false, 1, NO_LOCKS, 60);
        agentAccess(validator, "holder.view", false, 2, NO_LOCKS, 60);
        agentStore(validator, "holder.view", 1, 60, 601);
        agentStore(validator, "holder.view", 2, 60, 602);
        // Both views are built and never touched again; the holder keeps being read.
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "holder.view", false, 1, NO_LOCKS, 60);
            agentAccess(validator, "holder.view", false, 2, NO_LOCKS, 60);
        }
        assertFalse(validator.analyze().hasIssues(),
                "The field converged and neither published view was ever written again, which is "
                        + "what an idempotent value looks like. The #313 excuse is still owed, "
                        + "and the value evidence #326 adds must not take it away");
    }

    /**
     * A double-submit converges on the field and keeps mutating its payload, and fires (#326).
     *
     * <p>The loud half, and the blind spot #326 named. The access stream on {@code holder.job} is
     * identical to the view cache above - same miss checks, same racing stores, same settled
     * reads afterwards - because convergence is a property of the field. What differs is what was
     * stored: the submitted job keeps writing its own state after publication, so it was a side
     * effect rather than a value, and the work was done twice.
     */
    @Test
    @DisplayName("atomicity: a settled cache whose stored value keeps mutating fires (#326)")
    void atomicityDoubleSubmitShapedLikeACacheStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "holder.job", false, 1, NO_LOCKS, 70);
        agentAccess(validator, "holder.job", false, 2, NO_LOCKS, 70);
        agentStore(validator, "holder.job", 1, 70, 701);
        agentStore(validator, "holder.job", 2, 70, 702);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "holder.job", false, 1, NO_LOCKS, 70);
            agentAccess(validator, "holder.job", false, 2, NO_LOCKS, 70);
            // The losing submission is still running: its own state moves after publication,
            // which no idempotent value's does.
            agentAccess(validator, "job.state", true, 1, NO_LOCKS, 701);
        }
        assertTrue(validator.analyze().hasIssues(),
                "The field settled exactly as a view cache does, so convergence alone excused a "
                        + "double-submit: two threads both missed and both submitted, and the "
                        + "extra job kept running. A published value that keeps being written is "
                        + "a side effect, not an idempotent value");
    }

    /**
     * With no value evidence the answer is the one #313 gave, not a stricter one.
     *
     * <p>A stored identity of 0 means the weaver could not reach the value - a primitive write,
     * an older agent, or a payload of a type the agent does not weave, which includes every JDK
     * class. Absence of evidence must not become a finding, or the corpus's twenty-two
     * documented-safe subjects would go loud again on nothing at all.
     */
    @Test
    @DisplayName("atomicity: no value evidence keeps the previous answer (#326)")
    void atomicityWithoutValueEvidenceKeepsTheSettledExcuse() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "holder.opaque", false, 1, NO_LOCKS, 80);
        agentAccess(validator, "holder.opaque", false, 2, NO_LOCKS, 80);
        agentAccess(validator, "holder.opaque", true, 1, NO_LOCKS, 80);
        agentAccess(validator, "holder.opaque", true, 2, NO_LOCKS, 80);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "holder.opaque", false, 1, NO_LOCKS, 80);
            agentAccess(validator, "holder.opaque", false, 2, NO_LOCKS, 80);
        }
        assertFalse(validator.analyze().hasIssues(),
                "Nothing is known about what was stored, and nothing known must not become a "
                        + "finding. The rule only narrows where there is evidence to narrow with");
    }

    /**
     * A static single-check cache whose value goes quiet stays silent (#337).
     *
     * <p>The silent half of the static pair. Same shape as the instance view cache above, one
     * scope up: {@code if (INSTANCE == null) INSTANCE = create()} at class level. A static
     * field's receiver identity is 0 by construction, because the declaring class stands in for
     * a receiver it does not have, so this also pins that the value rule reads the same on the
     * identity-0 group as on a per-instance one.
     *
     * <p>Until the weaver reached a {@code PUTSTATIC}'s value this row could not exist: every
     * static store reported a stored identity of 0, which the rule reads as no evidence, so both
     * halves of the pair took the identity-0 path and neither said anything about the value.
     */
    @Test
    @DisplayName("atomicity: a static single-check cache whose value goes quiet stays silent (#337)")
    void atomicityStaticSingleCheckCacheWithQuiescentValueIsSilent() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "Registry.INSTANCE", false, 1, NO_LOCKS, 0);
        agentAccess(validator, "Registry.INSTANCE", false, 2, NO_LOCKS, 0);
        agentStore(validator, "Registry.INSTANCE", 1, 0, 901);
        agentStore(validator, "Registry.INSTANCE", 2, 0, 902);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "Registry.INSTANCE", false, 1, NO_LOCKS, 0);
            agentAccess(validator, "Registry.INSTANCE", false, 2, NO_LOCKS, 0);
        }
        assertFalse(validator.analyze().hasIssues(),
                "The static field converged and neither published instance was written again, "
                        + "which is what an effectively immutable singleton looks like. The #313 "
                        + "settle excuse is owed here exactly as it is on an instance field, and "
                        + "the static value evidence #337 adds must not take it away");
    }

    /**
     * A static lazy-init whose published value keeps mutating fires (#337).
     *
     * <p>The loud half, and the reason #337 was worth closing. The access stream on
     * {@code Registry.INSTANCE} is identical to the quiescent cache above, because convergence is
     * a property of the field and not of the payload. What differs is that the losing instance
     * keeps writing its own state after publication, so the class-scope miss check submitted the
     * work twice. Before the {@code PUTSTATIC} carried its value, this row was excused.
     */
    @Test
    @DisplayName("atomicity: a static lazy-init whose value keeps mutating fires (#337)")
    void atomicityStaticLazyInitWithLiveValueStillFires() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.markInvocationStart();
        agentAccess(validator, "Registry.INSTANCE", false, 1, NO_LOCKS, 0);
        agentAccess(validator, "Registry.INSTANCE", false, 2, NO_LOCKS, 0);
        agentStore(validator, "Registry.INSTANCE", 1, 0, 911);
        agentStore(validator, "Registry.INSTANCE", 2, 0, 912);
        for (int round = 0; round < 2; round++) {
            validator.markInvocationStart();
            agentAccess(validator, "Registry.INSTANCE", false, 1, NO_LOCKS, 0);
            agentAccess(validator, "Registry.INSTANCE", false, 2, NO_LOCKS, 0);
            // The instance that lost the race is still working: its own state moves after the
            // round that published it, which no effectively immutable singleton's does.
            agentAccess(validator, "Registry.state", true, 1, NO_LOCKS, 911);
        }
        assertTrue(validator.analyze().hasIssues(),
                "The static field settled exactly as the quiescent singleton does, so convergence "
                        + "alone excused a double initialization at class scope: both threads "
                        + "missed the check, both created, and the extra instance kept running. A "
                        + "published value that keeps being written is a side effect, not a value");
    }

    // ---- ConcurrentMapComputeRecursionDetector ----

    /**
     * A real re-entry, driven through a real {@code ConcurrentHashMap} rather than recorded by
     * hand (#341).
     *
     * <p>The sibling unit test drives the detector by calling {@code recordComputeStart} twice
     * directly, which pins the rule but says nothing about whether the shape it describes can
     * happen. This does: the nested {@code merge} runs inside the outer one's remapping function,
     * so both recordings are raised from inside a mapping function that really executed.
     *
     * <p>{@code merge} on a key that is already present is the shape that gets there.
     * {@code computeIfAbsent} on an absent key does not: the bin holds a reservation node and
     * {@code ConcurrentHashMap} throws {@code IllegalStateException("Recursive update")} before
     * the inner function runs, which is the reason this pair uses {@code merge} and the reason
     * the detector never sees that other shape. Here the bin holds a real node whose monitor the
     * re-entry re-acquires, monitors are reentrant, and the nested update is quietly overwritten
     * by the outer return value.
     */
    @Test
    @DisplayName("compute recursion: a merge whose function re-enters the same key fires (#341)")
    void computeRecursionFiresOnAMergeThatReallyReEnters() {
        ConcurrentMapComputeRecursionDetector detector =
                new ConcurrentMapComputeRecursionDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        map.put("k", "seed");
        Thread self = Thread.currentThread();

        map.merge("k", "outer", (oldOuter, newOuter) -> {
            detector.recordComputeStart(map, "k", self, "cache");
            try {
                return map.merge("k", "inner", (oldInner, newInner) -> {
                    detector.recordComputeStart(map, "k", self, "cache");
                    try {
                        return "nested";
                    } finally {
                        detector.recordComputeEnd(map, "k", self);
                    }
                });
            } finally {
                detector.recordComputeEnd(map, "k", self);
            }
        });

        assertTrue(detector.analyze().hasIssues(),
                "The nested merge ran inside the outer one's remapping function, so the same map, "
                        + "key and thread were inside a compute at once. If this is silent the "
                        + "detector has stopped seeing the only re-entry shape that reaches a "
                        + "mapping function at all, and its exposure is zero however many "
                        + "hand-written recordings still pass");
    }

    /**
     * The twin: the same call, recorded the same way, with a function that stays out of the map.
     *
     * <p>Without this half, a detector that reported every {@code recordComputeStart} would pass
     * the row above. The recorded shape is identical except for the one thing the detector is
     * looking for, so what separates them is re-entry and nothing else.
     */
    @Test
    @DisplayName("compute recursion: a merge whose function stays out of the map is silent (#341)")
    void computeRecursionSilentWhenTheFunctionDoesNotReEnter() {
        ConcurrentMapComputeRecursionDetector detector =
                new ConcurrentMapComputeRecursionDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        map.put("k", "seed");
        Thread self = Thread.currentThread();

        map.merge("k", "outer", (oldValue, newValue) -> {
            detector.recordComputeStart(map, "k", self, "cache");
            try {
                return oldValue + '+';
            } finally {
                detector.recordComputeEnd(map, "k", self);
            }
        });

        assertFalse(detector.analyze().hasIssues(),
                "One start, one end, no re-entry. A merge whose remapping function does not touch "
                        + "the map is the correct use of it, and reporting it would make the "
                        + "detector fire on every instrumented compute*");
    }

    /**
     * A nested compute on a <em>different</em> key of the same map, which used to be excused
     * (#343).
     *
     * <p>The shape example 40 ships to demonstrate this detector, and the one the detector could
     * not see: its evidence used to be keyed on map, key and thread together, so a mapping
     * function that reached the same map under another key was invisible. The contract it breaks
     * is not key-scoped. {@code ConcurrentHashMap.computeIfAbsent} says "the mapping function
     * must not modify this map", full stop.
     *
     * <p>Driven with {@code merge} over two seeded keys because that is deterministic. The
     * {@code computeIfAbsent} version of the same shape depends on whether the two keys land in
     * the same bin: measured over 200 fresh maps it ran and returned 198 times and threw twice,
     * which is a fine thing to report and a poor thing to assert on.
     */
    @Test
    @DisplayName("compute recursion: a nested compute on another key of the same map fires (#343)")
    void computeRecursionFiresWhenTheFunctionReachesAnotherKeyOfTheSameMap() {
        ConcurrentMapComputeRecursionDetector detector =
                new ConcurrentMapComputeRecursionDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        map.put("outer", "seed");
        map.put("inner", "seed");
        Thread self = Thread.currentThread();

        map.merge("outer", "v", (oldOuter, newOuter) -> {
            detector.recordComputeStart(map, "outer", self, "cache");
            try {
                return map.merge("inner", "v", (oldInner, newInner) -> {
                    detector.recordComputeStart(map, "inner", self, "cache");
                    try {
                        return "nested";
                    } finally {
                        detector.recordComputeEnd(map, "inner", self);
                    }
                });
            } finally {
                detector.recordComputeEnd(map, "outer", self);
            }
        });

        assertTrue(detector.analyze().hasIssues(),
                "The remapping function for 'outer' modified the same map under 'inner' while it "
                        + "was still running. That is the documented prohibition, and it is the "
                        + "shape most likely to be in code that ships because it usually returns "
                        + "normally. Silence here is the excuse #343 removed, coming back");
    }

    /**
     * The boundary that keeps the rule above from firing on ordinary code.
     *
     * <p>The same nesting, one map apart. A mapping function that consults or fills some
     * <em>other</em> structure is not what the contract forbids, and it is common: a cache whose
     * loader reads a second cache would report on every call if the rule keyed on the thread
     * alone. This is the half that makes the cross-key rule safe to turn on by default.
     */
    @Test
    @DisplayName("compute recursion: nesting into a different map is not reported (#343)")
    void computeRecursionSilentWhenTheNestedComputeIsOnAnotherMap() {
        ConcurrentMapComputeRecursionDetector detector =
                new ConcurrentMapComputeRecursionDetector();
        ConcurrentMap<String, String> outerMap = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> innerMap = new ConcurrentHashMap<>();
        outerMap.put("k", "seed");
        innerMap.put("k", "seed");
        Thread self = Thread.currentThread();

        outerMap.merge("k", "v", (oldOuter, newOuter) -> {
            detector.recordComputeStart(outerMap, "k", self, "outer-cache");
            try {
                return innerMap.merge("k", "v", (oldInner, newInner) -> {
                    detector.recordComputeStart(innerMap, "k", self, "inner-cache");
                    try {
                        return "nested";
                    } finally {
                        detector.recordComputeEnd(innerMap, "k", self);
                    }
                });
            } finally {
                detector.recordComputeEnd(outerMap, "k", self);
            }
        });

        assertFalse(detector.analyze().hasIssues(),
                "Two maps, so neither mapping function modified the map it was computing for. "
                        + "ConcurrentHashMap's prohibition is per map; a detector that reported "
                        + "this would fire on every layered cache in the world");
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

    @Test
    @DisplayName("digest: a declared external lock is recognised (the FP above, closed)")
    void digestDetectorIsSilentWhenOneDeclaredLockGuardsEveryAccess() throws InterruptedException {
        SharedMessageDigestDetector detector = new SharedMessageDigestDetector();
        MessageDigest digest = sha256();
        ReentrantLock lock = new ReentrantLock();
        Runnable update = () -> {
            try (var held = AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    digest.update((byte) 1);
                    detector.recordAccess(digest, "shared-digest", Thread.currentThread());
                } finally {
                    lock.unlock();
                }
            }
        };
        onTwoThreads(update, update);

        assertFalse(detector.analyze().hasIssues(),
                "Same shared MessageDigest and the same two threads as the pinned false positive "
                        + "above; the only difference is that this lock is declared, so "
                        + "SelfGuard.TrackedInstance intersects it in and one lock covers every "
                        + "access. This capability already existed - HeldLocks.intersect has "
                        + "handled declared locks since the guard-on-self probe grew into a "
                        + "lockset - but nothing pinned it, so it could have been lost in a "
                        + "refactor without a single test going red. That is what this is for.");
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

    // ---- FalseSharingDetector ----

    @Test
    @DisplayName("false sharing: silent by default, on the buggy shape and its twin alike")
    void falseSharingStaysSilentWhileTheExperimentalGateIsOff() throws InterruptedException {
        assertFalse(Boolean.getBoolean(FalseSharingDetector.EXPERIMENTAL_PROPERTY),
                "This pins the default, so it has to run with the property unset. Something "
                        + "earlier in this JVM set it and did not restore it.");

        FalseSharingDetector detector = new FalseSharingDetector();
        AdjacentFields shared = new AdjacentFields();
        onTwoThreads(
                () -> detector.recordFieldAccess(shared, "first", long.class),
                () -> {
                    detector.recordFieldAccess(shared, "first", long.class);
                    detector.recordFieldAccess(shared, "second", long.class);
                });

        assertFalse(detector.analyze().hasIssues(),
                "Two threads touched two fields eight bytes apart, which is the shape this "
                        + "detector looks for, and it must still report nothing: the offsets it "
                        + "computes are declaration-order arithmetic that real JVM layout "
                        + "(reordering, compressed oops, @Contended padding) does not follow, and "
                        + "keying is per class rather than per object. Findings are therefore "
                        + "opt-in. If this goes red, the experimental gate has been weakened and "
                        + "every consumer now gets pairs the detector cannot substantiate");
    }

    @Test
    @DisplayName("false sharing: the opt-in property is what produces findings, and nothing else")
    void falseSharingReportsOnlyWhenExplicitlyOptedIn() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        AdjacentFields shared = new AdjacentFields();
        onTwoThreads(
                () -> detector.recordFieldAccess(shared, "first", long.class),
                () -> {
                    detector.recordFieldAccess(shared, "first", long.class);
                    detector.recordFieldAccess(shared, "second", long.class);
                });

        // Recording is unaffected by the property, so opting in and re-analyzing the same
        // instance is enough - this needs no second run, which is what makes the pair testable
        // in one JVM at all.
        String previous = System.getProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        try {
            System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
            assertTrue(detector.analyze().hasIssues(),
                    "With the property set, the same recorded accesses must produce the pair. A "
                            + "gate that suppresses findings permanently would make the detector "
                            + "dead code rather than experimental, and the catalog's claim that "
                            + "it is opt-in would be false in the other direction");

            assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(detector.analyze().toString()),
                    "The failOn gate reads a finding's severity out of this text, and defaults to "
                            + "HIGH when it finds no marker, so before #291 an advisory about "
                            + "cache-line adjacency reached the gate ranked as though it proved "
                            + "data corruption. This detector is experimental and its findings are "
                            + "documented as uncorrelated with the phenomenon, so LOW is the only "
                            + "ranking it can carry");
        } finally {
            if (previous == null) {
                System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
            } else {
                System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, previous);
            }
        }
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

    /**
     * Two adjacent long fields: 8 bytes apart by the detector's declaration-order arithmetic,
     * so well inside the 64-byte cache line it looks for.
     */
    static class AdjacentFields {
        long first;
        long second;
    }

    // ---- ESSENTIALS preset: the detectors docs recommend for everyday CI ----

    @Test
    @DisplayName("lock leak: acquiring without releasing fires (true positive)")
    void lockLeakDetectorFiresOnAnUnreleasedLock() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "lock");
        Runnable leak = () -> detector.recordLockAcquired(lock, "lock");
        onTwoThreads(leak, leak);

        assertTrue(detector.analyze().hasIssues(),
                "two acquisitions and no release is the leak this detector exists for");
    }

    @Test
    @DisplayName("lock leak: the balanced twin stays silent (true negative)")
    void lockLeakDetectorStaysSilentWhenEveryAcquireIsReleased() throws InterruptedException {
        LockLeakDetector detector = new LockLeakDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "lock");
        Runnable balanced = () -> {
            lock.lock();
            detector.recordLockAcquired(lock, "lock");
            try {
                Thread.yield();
            } finally {
                detector.recordLockReleased(lock, "lock");
                lock.unlock();
            }
        };
        // A real lock here, unlike the leak case above: the balanced twin releases it, so the
        // second thread is never blocked. The leaking twin only records, because a ReentrantLock
        // genuinely left held would hang this test rather than fail it.
        onTwoThreads(balanced, balanced);

        assertFalse(detector.analyze().hasIssues(),
                "acquire and release counts match and no lock is held at analysis time, so this "
                        + "detector distinguishes the correct idiom from the broken one rather than "
                        + "counting how many threads touched the lock");
    }

    /**
     * One thread takes the lock and holds it until the other has timed out and backed off, so the
     * handled timeout happens on every run. {@code leak} decides whether the holder also re-enters
     * the lock without releasing the extra hold, the CounterService shape (#589).
     */
    private static Runnable holdUntilTheOtherTimesOut(ReentrantLockDetector detector,
            ReentrantLock lock, CountDownLatch otherTimedOut, boolean leak) {
        return () -> {
            try {
                if (lock.tryLock(20, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    detector.recordLockAcquired(lock, Thread.currentThread().getName());
                    try {
                        if (leak) {
                            lock.lock(); // a helper re-enters and never unlocks
                        }
                        otherTimedOut.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } finally {
                        detector.recordLockReleased(lock, Thread.currentThread().getName());
                        lock.unlock();
                    }
                } else {
                    detector.recordLockTimeout(lock); // handled: back off
                    otherTimedOut.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    @DisplayName("reentrant lock: a hold re-entered and never released fires (true positive)")
    void reentrantLockDetectorFiresOnAHoldLeftTaken() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");
        CountDownLatch timedOut = new CountDownLatch(1);
        Runnable body = holdUntilTheOtherTimesOut(detector, lock, timedOut, true);
        onTwoThreads(body, body);

        assertTrue(detector.analyze().hasIssues(),
                "both threads are gone and the lock is still taken: the recorded pair balanced, "
                        + "but the lock itself says a hold was never given back. Report:\n"
                        + detector.analyze());
    }

    @Test
    @DisplayName("reentrant lock: the twin whose tryLock times out and backs off stays silent (true negative)")
    void reentrantLockDetectorStaysSilentOnAHandledTimeout() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");
        CountDownLatch timedOut = new CountDownLatch(1);
        Runnable body = holdUntilTheOtherTimesOut(detector, lock, timedOut, false);
        onTwoThreads(body, body);

        assertEquals(0, timedOut.getCount(), "the premise: one tryLock really timed out");
        assertFalse(detector.analyze().hasIssues(),
                "the same contention and the same recorded timeout, handled by backing off, and the "
                        + "lock free at analysis. Until #589 the timeout alone was a HIGH finding. "
                        + "Report:\n" + detector.analyze());
    }

    @Test
    @DisplayName("reentrant lock: the same hold, taken by a thread still working at analysis, stays silent (true negative)")
    void reentrantLockDetectorStaysSilentOnAHoldItsHolderStillWorksUnder() throws InterruptedException {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            detector.recordLockAcquired(lock, "holder");
            try {
                held.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                detector.recordLockReleased(lock, "holder");
                lock.unlock();
            }
        }, "still-working-holder");
        holder.start();
        try {
            assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(lock.isLocked(), "the premise: the lock is taken at analysis");
            assertFalse(detector.analyze().hasIssues(),
                    "the lock is taken, but by a thread that is alive and not back in a pool, so it "
                            + "may still give it back. Until #609 this was reported as a leak. "
                            + "Report:\n" + detector.analyze());
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    @DisplayName("completable future: completing exceptionally with no handler fires (true positive)")
    void completableFutureExceptionDetectorFiresOnAnUnhandledFailure() throws InterruptedException {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "future");
        Runnable failIt = () -> {
            future.completeExceptionally(new IllegalStateException("boom"));
            detector.recordFutureCompleted(future, "future", false);
        };
        onTwoThreads(failIt, failIt);

        assertTrue(detector.analyze().hasIssues(),
                "a future that completed exceptionally with no handler registered loses the failure");
    }

    @Test
    @DisplayName("completable future: the handled twin stays silent (true negative)")
    void completableFutureExceptionDetectorStaysSilentWhenTheFailureIsHandled()
            throws InterruptedException {
        CompletableFutureExceptionDetector detector = new CompletableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "future");
        Runnable handleIt = () -> {
            IllegalStateException failure = new IllegalStateException("boom");
            detector.recordExceptionHandled(future, "future", failure);
            future.completeExceptionally(failure);
            detector.recordFutureCompleted(future, "future", false);
        };
        onTwoThreads(handleIt, handleIt);

        assertFalse(detector.analyze().hasIssues(),
                "the same failure, with a handler registered, is handled code; a detector that still "
                        + "fired here would be reporting the exception rather than the missing handler");
    }

    @Test
    @DisplayName("concurrent modification: modifying during iteration fires (true positive)")
    void concurrentModificationDetectorFiresOnModificationDuringIteration()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        detector.registerCollection(list, "list");
        Runnable modifyWhileIterating = () -> {
            detector.recordIterationStarted(list, "list");
            detector.recordModification(list, "list", "add");
            detector.recordIterationEnded(list, "list");
        };
        onTwoThreads(modifyWhileIterating, modifyWhileIterating);

        assertTrue(detector.analyze().hasIssues(),
                "a structural modification while an iterator is live is the bug this detector names");
    }

    @Test
    @DisplayName("concurrent modification: read-only concurrent iteration stays silent (true negative)")
    void concurrentModificationDetectorStaysSilentOnReadOnlyConcurrentIteration()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        list.add("value");
        detector.registerCollection(list, "list");
        Runnable iterateOnly = () -> {
            detector.recordIterationStarted(list, "list");
            for (String ignored : list) {
                // read, never write
            }
            detector.recordIterationEnded(list, "list");
        };
        onTwoThreads(iterateOnly, iterateOnly);

        assertFalse(detector.analyze().hasIssues(),
                "two threads iterating an ArrayList nobody mutates is correct code: they observe "
                        + "the same stable list and no iterator can tear. Until #494 analyze() "
                        + "reported any collection iterated by more than one thread, with no "
                        + "modification required and no lock consulted, at VERDICT tier - whose "
                        + "contract is that a finding means the code is wrong");
    }

    @Test
    @DisplayName("concurrent modification: a thread-safe collection stays silent (true negative)")
    void concurrentModificationDetectorStaysSilentOnAThreadSafeCollection() throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new CopyOnWriteArrayList<>();
        detector.registerCollection(list, "list");
        Runnable safeModify = () -> {
            list.add("value");
            detector.recordModification(list, "list", "add");
        };
        onTwoThreads(safeModify, safeModify);

        assertFalse(detector.analyze().hasIssues(),
                "two threads adding to a CopyOnWriteArrayList is the type being used as designed. "
                        + "Until #292 the detector reported it, because analyze() flagged any "
                        + "collection touched by more than one thread whether or not the collection "
                        + "was thread-safe and whether or not an iterator was ever live, which is a "
                        + "false positive on the ESSENTIALS preset");
    }

    @Test
    @DisplayName("concurrent modification: a snapshot iterator makes modification-during-iteration safe")
    void concurrentModificationDetectorStaysSilentWhenTheIteratorIsASnapshot()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new CopyOnWriteArrayList<>();
        detector.registerCollection(list, "list");
        Runnable modifyWhileIterating = () -> {
            detector.recordIterationStarted(list, "list");
            detector.recordModification(list, "list", "add");
            detector.recordIterationEnded(list, "list");
        };
        onTwoThreads(modifyWhileIterating, modifyWhileIterating);

        assertFalse(detector.analyze().hasIssues(),
                "modifying a CopyOnWriteArrayList while iterating it cannot throw: the iterator is a "
                        + "snapshot. The inferred finding is suppressed for types that cannot break");
    }

    @Test
    @DisplayName("concurrent modification: an explicitly observed CME is reported whatever the type")
    void concurrentModificationDetectorReportsAnObservedCmeEvenOnAThreadSafeCollection()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new CopyOnWriteArrayList<>();
        detector.registerCollection(list, "list");
        Runnable observed = () -> detector.recordModificationDuringIteration(list, "list", "add");
        onTwoThreads(observed, observed);

        assertTrue(detector.analyze().hasIssues(),
                "recordModificationDuringIteration is the caller saying it saw one, not this "
                        + "detector inferring it. Suppressing an observation because the type looks "
                        + "safe would be the library overruling the evidence");
    }

    @Test
    @DisplayName("concurrent modification: an externally locked ArrayList still fires (pinned false positive)")
    void concurrentModificationDetectorStillFiresOnAnExternallyLockedArrayList()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        Object guard = new Object();
        detector.registerCollection(list, "list");
        Runnable guardedModify = () -> {
            synchronized (guard) {
                list.add("value");
                detector.recordModification(list, "list", "add");
            }
        };
        onTwoThreads(guardedModify, guardedModify);

        assertTrue(detector.analyze().hasIssues(),
                "This is the limit that keeps CONCURRENT_MODIFICATIONS at PROMPT rather than "
                        + "VERDICT. An ArrayList guarded by a lock the detector was never told about "
                        + "is correct code, and the mutation-count finding still stands, exactly as "
                        + "it does for the other detectors with no lock model. Fixing #292 removed "
                        + "the findings the collection's own type ruled out, not the ones an "
                        + "invisible lock rules out");
    }

    /**
     * A genuinely thread-safe collection that does not live in {@code java.util.concurrent}.
     *
     * <p>Local rather than a real library class on purpose: the point is the model, not guava. The
     * detector decides safety from the class's package name, so any correct third-party collection
     * lands on the wrong side of it, and a local class shows that without a dependency.
     */
    private static final class ThreadSafeBag extends java.util.AbstractCollection<String> {
        private final List<String> backing = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean add(String value) {
            return backing.add(value);
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return backing.iterator();
        }

        @Override
        public int size() {
            return backing.size();
        }
    }

    /** The same bag, named the way the ecosystem names thread-safe collections. */
    private static final class ConcurrentBag extends java.util.AbstractCollection<String> {
        private final List<String> backing = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean add(String value) {
            return backing.add(value);
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return backing.iterator();
        }

        @Override
        public int size() {
            return backing.size();
        }
    }

    @Test
    @DisplayName("concurrent modification: a collection named by the concurrent convention stays silent (#395)")
    void concurrentModificationDetectorIsSilentOnACollectionNamedConcurrent()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        ConcurrentBag bag = new ConcurrentBag();
        detector.registerCollection(bag, "bag");
        Runnable mutate = () -> {
            bag.add("value");
            detector.recordModification(bag, "bag", "add");
        };
        onTwoThreads(mutate, mutate);

        assertFalse(detector.analyze().hasIssues(),
                "Byte for byte the same collection as the pinned row below, renamed. That is the "
                        + "whole of #395: only the JDK puts concurrent collections in "
                        + "java.util.concurrent, so a package test reads every correct "
                        + "third-party one as unsafe. The name is the only signal Java offers, "
                        + "and the ecosystem uses it consistently - measured against guava's "
                        + "ConcurrentHashMultiset and commons-collections4's "
                        + "SynchronizedCollection, both of which were reported before this and "
                        + "are silent after, with commons' documented-unsafe maps still firing.");
    }

    @Test
    @DisplayName("concurrent modification: a thread-safe collection outside java.util.concurrent still fires (pinned false positive)")
    void concurrentModificationDetectorFiresOnAThreadSafeCollectionItCannotRecognise()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        ThreadSafeBag bag = new ThreadSafeBag();
        detector.registerCollection(bag, "bag");
        Runnable mutate = () -> {
            bag.add("value");
            detector.recordModification(bag, "bag", "add");
        };
        onTwoThreads(mutate, mutate);

        assertTrue(detector.analyze().hasIssues(),
                "PINNED FALSE POSITIVE, and what is left of it after #395. The detector now "
                        + "recognises the naming convention the ecosystem actually uses - "
                        + "Concurrent*, CopyOnWrite*, Synchronized* - so guava's "
                        + "ConcurrentHashMultiset and commons-collections4's "
                        + "SynchronizedCollection are silent where they used to report. This bag "
                        + "is thread-safe and says so nowhere in its name, which is the residue: "
                        + "a name is the only signal Java offers, so a collection that keeps its "
                        + "safety to itself is still reported. "
                        + "A denylist would have closed this too, at the cost of going silent "
                        + "on commons-collections4's documented-unsafe maps, which is a worse "
                        + "trade for a detector whose job is finding them. If this ever goes "
                        + "silent, the model gained real evidence of thread-safety - flip the "
                        + "assertion and update detector-accuracy-eval.md.");
    }

    @Test
    @DisplayName("concurrent modification: a declared external lock is recognised (the FP above, closed)")
    void concurrentModificationDetectorIsSilentWhenOneDeclaredLockGuardsEveryMutation()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        ReentrantLock lock = new ReentrantLock();
        detector.registerCollection(list, "list");
        Runnable guardedModify = () -> {
            try (var held = AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    list.add("value");
                    detector.recordModification(list, "list", "add");
                } finally {
                    lock.unlock();
                }
            }
        };
        onTwoThreads(guardedModify, guardedModify);

        assertFalse(detector.analyze().hasIssues(),
                "Same ArrayList and the same two threads as the test above; the only difference is "
                        + "that this lock is declared, so it reaches the collection's lockset and "
                        + "covers every mutation. Reporting here would be reporting correct code "
                        + "the caller took the trouble to describe. The agent produces the same "
                        + "members without the declaration, by weaving MONITORENTER.");
    }

    @Test
    @DisplayName("concurrent modification: two different declared locks are still a race")
    void concurrentModificationDetectorFiresWhenEachThreadHoldsItsOwnLock()
            throws InterruptedException {
        ConcurrentModificationDetector detector = new ConcurrentModificationDetector();
        List<String> list = new ArrayList<>();
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        detector.registerCollection(list, "list");
        Runnable underFirst = mutateUnder(detector, list, first);
        Runnable underSecond = mutateUnder(detector, list, second);
        onTwoThreads(underFirst, underSecond);

        assertTrue(detector.analyze().hasIssues(),
                "Both threads held a declared lock, and no single lock covered both mutations, so "
                        + "they never excluded each other. This is the case a model that only "
                        + "asked \"was any lock held\" would get wrong, and it is why the lockset "
                        + "intersects rather than counting.");
    }

    /**
     * Mutates the shared list under {@code lock} and records the mutation whatever the list did.
     *
     * <p>The two callers' locks deliberately do not exclude each other, so the raced
     * {@code ArrayList} itself can corrupt and throw from {@code add}: measured at 5 in 3
     * million collisions, as {@code ArrayIndexOutOfBoundsException: Index 1 out of bounds for
     * length 0}, a stale {@code elementData} read beside the other thread's {@code size} write.
     * That crash is the raced object's symptom, not this eval's subject, and it was #413: the
     * dying worker skipped its recording and the detector was silenced by its own scenario. The
     * mutation attempt happened either way, so it is recorded either way.
     */
    @Test
    @DisplayName("stamped lock: an optimistic read used without validating fires (true positive)")
    void stampedLockDetectorFiresWhenAValidationFailureIsIgnored() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "config");
        Runnable readAndTrustIt = () -> {
            long stamp = lock.tryOptimisticRead();
            detector.recordOptimisticRead(lock, "config", stamp);
            // A writer intervened, and the value is used anyway: no fallback, no retry.
            detector.recordOptimisticValidation(lock, "config", stamp, false);
        };
        onTwoThreads(readAndTrustIt, readAndTrustIt);

        assertTrue(detector.analyze().hasIssues(),
                "a validation that failed and was never followed by a read lock or a retry means "
                        + "the stale value was used as read, which is the defect this detector names");
    }

    @Test
    @DisplayName("stamped lock: validate-then-readLock stays silent (true negative)")
    void stampedLockDetectorStaysSilentOnTheDocumentedFallback() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "config");
        Runnable readWithFallback = () -> {
            long stamp = lock.tryOptimisticRead();
            detector.recordOptimisticRead(lock, "config", stamp);
            if (!lock.validate(stamp)) {
                detector.recordOptimisticValidation(lock, "config", stamp, false);
                long readStamp = lock.readLock();
                detector.recordReadLock(lock, "config", readStamp);
                lock.unlockRead(readStamp);
                detector.recordUnlock(lock, "config", readStamp);
            } else {
                detector.recordOptimisticValidation(lock, "config", stamp, true);
            }
        };
        onTwoThreads(readWithFallback, readWithFallback);

        assertFalse(detector.analyze().hasIssues(),
                "validate() returning false and the caller taking the read lock is the canonical "
                        + "StampedLock idiom - the case the class exists for. Until #496 the "
                        + "detector reported every failed validation, so the finding fired "
                        + "stochastically whenever a writer happened to intervene, which is exactly "
                        + "the situation this code handles correctly");
    }

    @Test
    @DisplayName("stamped lock: a write stamp dropped on the early-return path fires with no declaration (true positive)")
    void stampedLockDetectorFiresOnAWriteStampNeverReleased() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");
        Runnable writeAndReturnEarly = () -> {
            long stamp = lock.tryWriteLock();
            detector.recordWriteLock(lock, "ledger", stamp);
            if (stamp == 0L) {
                return; // the other thread already holds it, and will hold it forever
            }
            // An early return between writeLock() and unlockWrite() with no finally block.
        };
        onTwoThreads(writeAndReturnEarly, writeAndReturnEarly);

        assertTrue(detector.analyze().hasIssues(),
                "one thread took the write stamp and never released it; the lock is still "
                        + "write-held at analysis and the acquisition was never matched. Until "
                        + "#588 this needed the body to call recordStampNotReleased itself");
    }

    @Test
    @DisplayName("stamped lock: the same write released in a finally block stays silent (true negative)")
    void stampedLockDetectorStaysSilentWhenTheStampIsReleasedInFinally() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "ledger");
        Runnable writeAndRelease = () -> {
            long stamp = lock.writeLock();
            detector.recordWriteLock(lock, "ledger", stamp);
            try {
                // the write
            } finally {
                lock.unlockWrite(stamp);
                detector.recordUnlock(lock, "ledger", stamp);
            }
        };
        onTwoThreads(writeAndRelease, writeAndRelease);

        assertFalse(detector.analyze().hasIssues(),
                "every write stamp came back and the lock is free: " + detector.analyze());
    }

    @Test
    @DisplayName("stamped lock: a read stamp released again on an error path, while another reader holds, fires (true positive)")
    void stampedLockDetectorFiresOnAReadStampReleasedTwice() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "index");
        CountDownLatch readerHolds = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        Thread reader = new Thread(() -> readAndReleaseAfter(detector, lock, readerHolds, writerDone));
        reader.start();
        readerHolds.await();

        long stamp = lock.readLock();
        detector.recordReadLock(lock, "index", stamp);
        try {
            // read, then fail
        } finally {
            lock.unlockRead(stamp);
            detector.recordUnlock(lock, "index", stamp);
        }
        // An error handler that releases again, as if the finally block had not run.
        lock.unlockRead(stamp);
        detector.recordUnlock(lock, "index", stamp);
        writerDone.countDown();
        reader.join();

        assertTrue(detector.analyze().hasIssues(),
                "the second release is accepted by the lock and takes the other reader's hold; "
                        + "the lock only throws later, in that reader's thread (#604): " + detector.analyze());
    }

    @Test
    @DisplayName("stamped lock: the same read released once in its finally block stays silent (true negative)")
    void stampedLockDetectorStaysSilentOnAReadStampReleasedOnce() throws InterruptedException {
        StampedLockDetector detector = new StampedLockDetector();
        StampedLock lock = new StampedLock();
        detector.registerLock(lock, "index");
        CountDownLatch readerHolds = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        Thread reader = new Thread(() -> readAndReleaseAfter(detector, lock, readerHolds, writerDone));
        reader.start();
        readerHolds.await();

        long stamp = lock.readLock();
        detector.recordReadLock(lock, "index", stamp);
        try {
            // read, then fail
        } finally {
            lock.unlockRead(stamp);
            detector.recordUnlock(lock, "index", stamp);
        }
        writerDone.countDown();
        reader.join();

        assertFalse(detector.analyze().hasIssues(),
                "each hold released exactly once, by the code that took it: " + detector.analyze());
    }

    /** Takes a read hold, keeps it until {@code release} opens, then releases it. */
    private static void readAndReleaseAfter(StampedLockDetector detector, StampedLock lock,
                                            CountDownLatch holds, CountDownLatch release) {
        long stamp = lock.readLock();
        detector.recordReadLock(lock, "index", stamp);
        holds.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            lock.unlockRead(stamp);
            detector.recordUnlock(lock, "index", stamp);
        } catch (IllegalMonitorStateException holdAlreadyTaken) {
            // The symptom of the other thread's double release, far from its cause.
        }
    }

    private static Runnable mutateUnder(ConcurrentModificationDetector detector,
                                        List<String> list, ReentrantLock lock) {
        return () -> {
            try (var held = AsyncTestContext.holdingLock(lock)) {
                lock.lock();
                try {
                    try {
                        list.add("value");
                    } catch (RuntimeException corruptedByTheRace) {
                        // Expected rarely; see the javadoc above.
                    }
                    detector.recordModification(list, "list", "add");
                } finally {
                    lock.unlock();
                }
            }
        };
    }

    @Test
    @DisplayName("resource leak: opening without closing fires (true positive)")
    void resourceLeakDetectorFiresOnAnUnclosedResource() throws InterruptedException {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        Object connection = new Object();
        detector.registerResource(connection, "db", "Connection");
        Runnable leak = () -> detector.recordResourceOpened(connection, "db");
        onTwoThreads(leak, leak);

        assertTrue(detector.analyze().hasIssues(),
                "two opens and no close is the leak this detector exists for");
    }

    @Test
    @DisplayName("resource leak: the try-with-resources twin stays silent (true negative)")
    void resourceLeakDetectorStaysSilentWhenEveryOpenIsClosed() throws InterruptedException {
        ResourceLeakDetector detector = new ResourceLeakDetector();
        Object connection = new Object();
        detector.registerResource(connection, "db", "Connection");
        Runnable balanced = () -> {
            detector.recordResourceOpened(connection, "db");
            detector.recordResourceClosed(connection, "db");
        };
        onTwoThreads(balanced, balanced);

        assertFalse(detector.analyze().hasIssues(),
                "opens and closes balance and nothing is open at analysis time, so the detector "
                        + "distinguishes the correct idiom from the leak rather than counting how "
                        + "many threads touched the resource");
    }

    @Test
    @DisplayName("interrupt: swallowing InterruptedException fires (true positive)")
    void interruptMonitorFiresWhenTheFlagIsNeverRestored() throws InterruptedException {
        InterruptMonitor monitor = new InterruptMonitor();
        Runnable swallow = () -> monitor.recordInterruptException(new InterruptedException("caught"));
        onTwoThreads(swallow, swallow);

        assertTrue(monitor.analyze().hasIssues(),
                "catching InterruptedException without restoring the flag loses the cancellation "
                        + "signal for every caller above this frame");
    }

    @Test
    @DisplayName("interrupt: the catch-and-restore twin stays silent (true negative)")
    void interruptMonitorStaysSilentWhenTheFlagIsRestored() throws InterruptedException {
        InterruptMonitor monitor = new InterruptMonitor();
        Runnable restore = () -> {
            monitor.recordInterruptException(new InterruptedException("caught"));
            monitor.recordInterruptRestored();
        };
        onTwoThreads(restore, restore);

        assertFalse(monitor.analyze().hasIssues(),
                "catch-and-restore is the idiom this detector's own fix advice recommends, so it "
                        + "must not be reported as an ignored interrupt");
    }

    @Test
    @DisplayName("uncaught handler: a thread that throws with no handler fires (true positive)")
    void uncaughtExceptionHandlerDetectorFiresWhenNoHandlerIsInstalled() throws InterruptedException {
        UncaughtExceptionHandlerDetector detector = new UncaughtExceptionHandlerDetector();
        Runnable throwWithoutHandler = () -> {
            detector.recordThreadStart(Thread.currentThread());
            detector.recordUncaughtException(Thread.currentThread(), new IllegalStateException("boom"));
        };
        onTwoThreads(throwWithoutHandler, throwWithoutHandler);

        assertTrue(detector.analyze().hasIssues(),
                "a worker that dies with no custom handler prints to stderr and the submitter "
                        + "never learns the task failed");
    }

    @Test
    @DisplayName("uncaught handler: the same failure with a handler installed stays silent (true negative)")
    void uncaughtExceptionHandlerDetectorStaysSilentWhenAHandlerIsInstalled()
            throws InterruptedException {
        UncaughtExceptionHandlerDetector detector = new UncaughtExceptionHandlerDetector();
        Runnable throwWithHandler = () -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> { /* observed */ });
            detector.recordThreadStart(Thread.currentThread());
            detector.recordUncaughtException(Thread.currentThread(), new IllegalStateException("boom"));
        };
        onTwoThreads(throwWithHandler, throwWithHandler);

        assertFalse(detector.analyze().hasIssues(),
                "the same exception on a thread whose handler will see it is handled code; a "
                        + "detector that still fired here would be reporting the exception rather "
                        + "than the missing handler");
    }

    @Test
    @DisplayName("completion leak: a future nobody completes fires (true positive)")
    void completionLeakDetectorFiresOnAFutureThatIsNeverCompleted() throws InterruptedException {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        Runnable create = () -> detector.recordFutureCreated(future, "future");
        onTwoThreads(create, create);

        assertTrue(detector.analyze().hasIssues(),
                "a future created and never completed leaves every caller awaiting it parked");
    }

    @Test
    @DisplayName("completion leak: the completed twin stays silent (true negative)")
    void completionLeakDetectorStaysSilentWhenTheFutureIsCompleted() throws InterruptedException {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        Runnable createAndComplete = () -> {
            detector.recordFutureCreated(future, "future");
            future.complete("value");
            detector.recordFutureCompleted(future, "future");
        };
        onTwoThreads(createAndComplete, createAndComplete);

        assertFalse(detector.analyze().hasIssues(),
                "a future both threads see completed is not leaked, and completing it twice is "
                        + "not this detector's concern");
    }

    @Test
    @DisplayName("thread leak: a thread started and never joined fires (true positive)")
    void threadLeakDetectorFiresOnAThreadStillAlive() throws InterruptedException {
        ThreadLeakDetector detector = new ThreadLeakDetector();
        CountDownLatch release = new CountDownLatch(1);
        Thread leaked = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "leaked-worker");
        leaked.setDaemon(true);
        try {
            leaked.start();
            detector.recordThreadStart(leaked, "leaked-worker");

            assertTrue(detector.analyzeLeaks().hasIssues(),
                    "a thread started by the test body and still alive when the run ends outlives "
                            + "the test that created it");
        } finally {
            release.countDown();
            leaked.join(2_000);
        }
    }

    @Test
    @DisplayName("thread leak: the joined twin stays silent (true negative)")
    void threadLeakDetectorStaysSilentWhenTheThreadTerminated() throws InterruptedException {
        ThreadLeakDetector detector = new ThreadLeakDetector();
        Thread worker = new Thread(() -> { /* returns immediately */ }, "joined-worker");
        worker.start();
        detector.recordThreadStart(worker, "joined-worker");
        worker.join(2_000);
        detector.recordThreadEnd(worker);

        assertFalse(detector.analyzeLeaks().hasIssues(),
                "a thread that was joined and recorded as ended is not a leak; auto mode, which "
                        + "watches the global thread count, is off unless enableAutoMode() is called");
    }

    // ---- LockDowngradeDetector ----

    @Test
    @DisplayName("lock downgrade: releasing write before taking read, with a writer in the gap, fires (#355)")
    void lockDowngradeFiresWhenAWriterIsObservedInsideTheGap() throws InterruptedException {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        java.util.concurrent.locks.ReentrantReadWriteLock lock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordWriteLockReleased(lock, "store");     // the gap opens
        Thread interloper = new Thread(() -> {
            detector.recordWriteLockAcquired(lock, "store"); // and is used
            detector.recordWriteLockReleased(lock, "store");
        }, "interloper");
        interloper.start();
        interloper.join(5_000);
        detector.recordReadLockAcquired(lock, "store");      // the gap closes
        detector.recordReadLockReleased(lock, "store");

        assertTrue(detector.analyze().hasIssues(),
                "the lock was free between the write release and the read acquire, and another "
                        + "thread wrote in it; the read need not return what the writer wrote");
    }

    @Test
    @DisplayName("lock downgrade: the correct downgrade stays silent however contended (#355)")
    void lockDowngradeStaysSilentOnTheCorrectDowngrade() throws InterruptedException {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        java.util.concurrent.locks.ReentrantReadWriteLock lock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordReadLockAcquired(lock, "store");      // read taken while write is held
        detector.recordWriteLockReleased(lock, "store");
        Thread interloper = new Thread(() -> {
            detector.recordWriteLockAcquired(lock, "store");
            detector.recordWriteLockReleased(lock, "store");
        }, "interloper");
        interloper.start();
        interloper.join(5_000);
        detector.recordReadLockReleased(lock, "store");

        assertFalse(detector.analyze().hasIssues(),
                "there is no moment at which the downgrading thread holds neither lock, so no "
                        + "gap exists for a writer to enter: " + detector.analyze());
    }

    @Test
    @DisplayName("lock downgrade: the same shape with nobody in the gap stays silent (pinned false negative)")
    void lockDowngradeStaysSilentWithoutAnObservedWriter() {
        LockDowngradeDetector detector = new LockDowngradeDetector();
        java.util.concurrent.locks.ReentrantReadWriteLock lock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();

        detector.recordWriteLockAcquired(lock, "store");
        detector.recordWriteLockReleased(lock, "store");
        detector.recordReadLockAcquired(lock, "store");
        detector.recordReadLockReleased(lock, "store");

        assertFalse(detector.analyze().hasIssues(),
                "a write, a release and a later read is also what correct code produces when "
                        + "the read is unrelated, and the records cannot tell the two apart. "
                        + "This false negative buys the absence of a finding on correct code; "
                        + "if the detector ever learns to distinguish them, flip this assertion "
                        + "and update detector-accuracy-eval.md");
    }

    // ---- ConstructorSafetyValidator ----

    @Test
    @DisplayName("constructor safety: an object another thread reaches mid-construction fires (true positive)")
    void constructorSafetyFiresOnPublicationDuringConstruction() throws InterruptedException {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object escaping = new Object();
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch seen = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                published.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            validator.recordFieldAccess(escaping, "name", System.nanoTime());
            seen.countDown();
        }, "constructor-safety-reader");
        reader.setDaemon(true);
        reader.start();

        // Inside the "constructor": the reference escapes before construction ends.
        validator.recordConstructionStart(escaping);
        published.countDown();
        assertTrue(seen.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the reader thread must have run before this assertion means anything");
        validator.recordConstructionEnd(escaping);
        reader.join(2_000);

        assertTrue(validator.validateConstructorSafety().hasIssues(),
                "another thread read a field of the object before its constructor returned; "
                        + "that is unsafe publication, the finding this validator exists for");
    }

    @Test
    @DisplayName("constructor safety: an ordinary fast constructor stays silent (true negative, #357)")
    void constructorSafetyStaysSilentOnAnOrdinaryConstructor() throws InterruptedException {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object settled = new Object();

        // A constructor that assigns a few fields, instrumented start and end, and read only
        // after it returned. This completes well inside a microsecond, which used to be
        // reported as "possibly incomplete construction" on every ordinary object.
        validator.recordConstructionStart(settled);
        validator.recordConstructionEnd(settled);

        Thread reader = new Thread(
                () -> validator.recordFieldAccess(settled, "name", System.nanoTime()),
                "constructor-safety-late-reader");
        reader.start();
        reader.join(2_000);

        ConstructorSafetyValidator.ConstructorSafetyReport report =
                validator.validateConstructorSafety();
        assertFalse(report.hasIssues(),
                "a read after the constructor returned is not unsafe publication. Report:\n" + report);
        assertTrue(report.possiblyIncompleteConstructions.isEmpty(),
                "elapsed time cannot tell a completed construction from an incomplete one, and "
                        + "this one demonstrably completed. Report:\n" + report);
    }

    // ---- ThreadLocalMonitor ----

    @Test
    @DisplayName("thread local: set on two threads and never removed fires (true positive)")
    void threadLocalMonitorFiresOnSetWithoutRemove() throws InterruptedException {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> requestUser = new ThreadLocal<>();
        Runnable setOnly = () -> {
            requestUser.set("user-" + Thread.currentThread().threadId());
            monitor.recordThreadLocalInit(requestUser, "REQUEST_USER");
        };
        onTwoThreads(setOnly, setOnly);

        assertTrue(monitor.analyzeThreadLocalLeaks().hasIssues(),
                "a ThreadLocal set and never removed outlives its task on a pooled thread");
    }

    @Test
    @DisplayName("thread local: the remove()-in-finally twin stays silent (true negative)")
    void threadLocalMonitorStaysSilentWhenRemovedInFinally() throws InterruptedException {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> requestUser = new ThreadLocal<>();
        Runnable setAndRemove = () -> {
            requestUser.set("user-" + Thread.currentThread().threadId());
            monitor.recordThreadLocalInit(requestUser, "REQUEST_USER");
            try {
                requestUser.get();
            } finally {
                requestUser.remove();
                monitor.recordThreadLocalCleanup(requestUser);
            }
        };
        onTwoThreads(setAndRemove, setAndRemove);

        assertFalse(monitor.analyzeThreadLocalLeaks().hasIssues(),
                "every set was matched by a remove() in a finally block; there is no leak to "
                        + "report. Report:\n" + monitor.analyzeThreadLocalLeaks());
    }

    // ---- ExchangerDetector ----

    @Test
    @DisplayName("exchanger: an odd caller left blocked in an untimed exchange fires (true positive)")
    void exchangerFiresWhenAnOddCallerIsLeftWithoutAPartner() throws InterruptedException {
        ExchangerDetector detector = new ExchangerDetector();
        java.util.concurrent.Exchanger<String> exchanger = new java.util.concurrent.Exchanger<>();
        detector.registerExchanger(exchanger, "sync");

        Runnable untimed = () -> {
            detector.recordExchangeStart(exchanger, "sync");
            try {
                detector.recordExchangeComplete(exchanger, "sync", exchanger.exchange("payload"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // this test's cleanup, after the analysis
            }
        };
        onTwoThreads(untimed, untimed);

        // The bug: a third caller of an exchange that has no timeout, with nobody left to pair with.
        Thread odd = new Thread(untimed, "odd-exchanger-caller");
        odd.setDaemon(true);
        odd.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (odd.getState() != Thread.State.WAITING) {
                assertTrue(System.nanoTime() < deadline, "the odd caller never parked in exchange()");
                Thread.sleep(5);
            }
            assertTrue(detector.analyze().hasIssues(),
                    "the third caller is parked in exchange() for good, which is the orphaned "
                            + "rendezvous this detector exists to report. Report: "
                            + detector.analyze());
        } finally {
            odd.interrupt();
            odd.join(2_000);
        }
    }

    @Test
    @DisplayName("exchanger: the twin whose odd caller times out and handles it stays silent (true negative)")
    void exchangerStaysSilentWhenTheOddCallerHandlesItsTimeout() throws InterruptedException {
        ExchangerDetector detector = new ExchangerDetector();
        java.util.concurrent.Exchanger<String> exchanger = new java.util.concurrent.Exchanger<>();
        detector.registerExchanger(exchanger, "sync");

        Runnable timed = () -> {
            detector.recordExchangeStart(exchanger, "sync");
            try {
                detector.recordExchangeComplete(exchanger, "sync",
                        exchanger.exchange("payload", 20, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                detector.recordTimeout(exchanger); // the fix: give up, and say so
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        onTwoThreads(timed, timed);
        timed.run(); // the same odd caller, which now cannot be left behind

        assertFalse(detector.analyze().hasIssues(),
                "every exchange ended: the pair completed and the odd caller timed out and handled "
                        + "it, which is the report's own prescribed fix (#585). Report: "
                        + detector.analyze());
    }

    @Test
    @DisplayName("countdown latch: a worker that never signals makes the waiter time out (true positive)")
    void countDownLatchFiresWhenAWorkerNeverSignals() throws InterruptedException {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch startup = new CountDownLatch(2);
        detector.registerLatch(startup, "startup", 2);

        Runnable signals = () -> {
            detector.recordCountDown(startup);
            startup.countDown();
        };
        Runnable forgets = () -> { }; // the bug: this worker never reports itself ready
        onTwoThreads(signals, forgets);

        boolean fell = startup.await(20, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse(fell, "one countDown of two arrived, so the latch cannot reach zero");
        detector.recordTimeout(startup);

        assertTrue(detector.analyze().hasIssues(),
                "a latch awaited to expiry and never counted down to zero is the coordination "
                        + "failure this detector exists to report");
    }

    @Test
    @DisplayName("countdown latch: the twin that signals late stays silent (true negative)")
    void countDownLatchStaysSilentWhenTheLatchOnlyFellLate() throws InterruptedException {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch startup = new CountDownLatch(2);
        detector.registerLatch(startup, "startup", 2);

        // The waiter gives up before the workers have run. Nothing is wrong here: the wait was
        // simply shorter than the start-up it was waiting for.
        assertFalse(startup.await(1, java.util.concurrent.TimeUnit.MILLISECONDS),
                "neither worker has run yet");
        detector.recordTimeout(startup);

        Runnable signals = () -> {
            detector.recordCountDown(startup);
            startup.countDown();
        };
        onTwoThreads(signals, signals);

        assertTrue(startup.await(1, java.util.concurrent.TimeUnit.SECONDS),
                "both workers signalled, so the second wait completes");
        detector.recordAwaitSuccess(startup);

        assertFalse(detector.analyze().hasIssues(),
                "the latch reached zero, which a later await proved. A CountDownLatch only "
                        + "counts down and never blocks again once it is at zero, so the earlier "
                        + "timeout was a wait that started too early rather than a countDown() "
                        + "that never came (#477). Report: " + detector.analyze());
    }

    /**
     * Starts a consumer that waits on {@code notEmpty} until {@code ready[0]}, recording each
     * await and its exit, and returns once the consumer is inside its first await. Every read and
     * write of {@code ready} happens under {@code lock}.
     */
    private static Thread parkedConsumer(ConditionVariableDetector detector, ReentrantLock lock,
            java.util.concurrent.locks.Condition notEmpty, boolean[] ready) throws InterruptedException {
        CountDownLatch waiting = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            lock.lock();
            try {
                while (!ready[0]) {
                    detector.recordAwait(notEmpty, "not-empty");
                    waiting.countDown();
                    boolean signalled = notEmpty.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    detector.recordAwaitExit(notEmpty, "not-empty", !signalled);
                    if (!signalled) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        consumer.setDaemon(true);
        consumer.start();
        assertTrue(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS), "consumer never waited");
        return consumer;
    }

    @Test
    @DisplayName("condition variable: the producer signals the wrong condition, the consumer stays parked (true positive)")
    void conditionVariableFiresWhenTheProducerSignalsTheWrongCondition() throws InterruptedException {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        java.util.concurrent.locks.Condition notFull = lock.newCondition();
        detector.registerCondition(notEmpty, "not-empty");
        detector.registerCondition(notFull, "not-full");
        boolean[] ready = {false};

        Thread consumer = parkedConsumer(detector, lock, notEmpty, ready);
        lock.lock();   // acquirable only once the consumer's await has released it
        try {
            ready[0] = true;
            detector.recordSignal(notFull, "not-full", false);   // the bug: nobody waits on notFull
            notFull.signal();
        } finally {
            lock.unlock();
        }

        try {
            var report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the item is ready but the consumer is parked on a condition nobody signalled. "
                            + "Report:\n" + report);
        } finally {
            consumer.interrupt();
            consumer.join();
        }
    }

    @Test
    @DisplayName("condition variable: the twin that signals the consumer's condition stays silent (true negative)")
    void conditionVariableStaysSilentWhenTheProducerSignalsTheRightCondition() throws InterruptedException {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        java.util.concurrent.locks.Condition notFull = lock.newCondition();
        detector.registerCondition(notEmpty, "not-empty");
        detector.registerCondition(notFull, "not-full");
        boolean[] ready = {false};

        Thread consumer = parkedConsumer(detector, lock, notEmpty, ready);
        lock.lock();
        try {
            ready[0] = true;
            detector.recordSignal(notEmpty, "not-empty", false);
            notEmpty.signal();
            // A second producer signalling with nobody waiting is ordinary predicate-guarded code.
            detector.recordSignal(notFull, "not-full", true);
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
        consumer.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the consumer was woken by the signal on its own condition and saw the item; a "
                        + "signal into an empty condition is not a lost wakeup (#583). Report:\n"
                        + report);
    }

    @Test
    @DisplayName("phaser: two workers leave a phaser created for one party (true positive)")
    void phaserFiresWhenAWorkerArrivesAfterThePartiesRanOut() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        java.util.concurrent.Phaser done = new java.util.concurrent.Phaser(1);   // the bug: one party for two workers
        detector.registerPhaser(done, "done", 1);

        // The workers leave one after the other. Colliding on a barrier would not make the
        // defect more visible: a second arrival inside the first one's advance window throws
        // IllegalStateException into the body, which the runner already reports, instead of
        // returning the negative phase this detector reads.
        Runnable finish = () -> detector.recordArrival(done, done.arriveAndDeregister());
        Thread first = new Thread(finish, "first-worker");
        first.start();
        first.join();
        Thread second = new Thread(finish, "second-worker");
        second.start();
        second.join();

        assertTrue(done.isTerminated(), "the first deregistration took the count to zero");
        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the second worker's arriveAndDeregister returned a negative phase: the phaser had "
                        + "already ended, so that worker was never coordinated with. Report:\n"
                        + report);
    }

    @Test
    @DisplayName("phaser: the twin created for both workers ends in termination and stays silent (true negative)")
    void phaserStaysSilentWhenEveryWorkerIsCounted() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        java.util.concurrent.Phaser done = new java.util.concurrent.Phaser(2);
        detector.registerPhaser(done, "done", 2);

        Runnable finish = () -> detector.recordArrival(done, done.arriveAndDeregister());
        onTwoThreads(finish, finish);

        assertTrue(done.isTerminated(), "the last deregistration terminates the phaser");
        detector.recordTermination(done);
        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "both workers were counted and both left; termination at zero parties is how a "
                        + "phaser ends, not a finding (#587). Report:\n" + report);
    }

    /**
     * Runs a consumer that waits on {@code monitor} for {@code ready[0]}, guarded by {@code while}
     * or by {@code if}, and records each wait and its return. Nobody notifies until the consumer
     * has returned unsignalled at least once, so the only difference between the two runs is
     * whether that return is re-checked.
     */
    private static void wakeupConsumer(WakeupDetector detector, Object monitor, boolean[] ready,
            boolean loop) throws InterruptedException {
        CountDownLatch returnedOnce = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            synchronized (monitor) {
                boolean first = true;
                while (!ready[0] && (loop || first)) {
                    first = false;
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    detector.recordWaitExit(monitor, ready[0]);
                    returnedOnce.countDown();
                }
            }
        });
        consumer.setDaemon(true);
        consumer.start();
        assertTrue(returnedOnce.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "consumer never returned from wait");
        synchronized (monitor) {
            ready[0] = true;
            detector.recordNotify(monitor, true);
            monitor.notifyAll();
        }
        consumer.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10));
        assertFalse(consumer.isAlive(), "consumer did not finish");
    }

    @Test
    @DisplayName("wakeup: an if-guarded wait returns with no notify and the consumer proceeds (true positive)")
    void wakeupFiresWhenAnIfGuardProceedsOnAnUnsignalledReturn() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        wakeupConsumer(detector, new Object(), new boolean[] {false}, false);

        var report = detector.analyzeWakeups();
        assertTrue(report.hasIssues(),
                "the consumer's wait returned before anyone notified and the if guard let it go "
                        + "on with the flag still false. Report:\n" + report);
    }

    @Test
    @DisplayName("wakeup: the while-loop twin waits again after the same return and stays silent (true negative)")
    void wakeupStaysSilentWhenTheWhileLoopWaitsAgain() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] ready = {false};
        wakeupConsumer(detector, monitor, ready, true);
        // A second producer notifying with nobody waiting is ordinary flag-guarded code.
        synchronized (monitor) {
            detector.recordNotify(monitor, true);
            monitor.notifyAll();
        }

        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "every unsignalled return was followed by another wait, and a notify into an "
                        + "empty monitor is not a lost wakeup (#590). Report:\n" + report);
    }

    /** Keeps the calling timer task busy until the wall clock is strictly past {@code instantMs}. */
    private static void holdTimerThreadUntilPast(long instantMs) {
        while (System.currentTimeMillis() <= instantMs) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("timer: a task that falls due while another holds the only thread is reported (true positive)")
    void timerDetectorFiresOnATaskStarvedByAnotherOnTheSameThread() throws InterruptedException {
        TimerDetector detector = new TimerDetector();
        java.util.Timer timer = new java.util.Timer("eval-starved", true);
        try {
            detector.registerTimer(timer, "eval-timer");
            CountDownLatch reminderRan = new CountDownLatch(1);
            java.util.TimerTask reminder = new java.util.TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, "eval-timer", this, "reminder");
                    detector.recordTaskComplete(timer, "eval-timer", "reminder");
                    reminderRan.countDown();
                }
            };
            timer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, "eval-timer", this, "report");
                    timer.schedule(reminder, 2);
                    // The bug: slow work on the timer's one thread, past the reminder's due time.
                    holdTimerThreadUntilPast(reminder.scheduledExecutionTime());
                    detector.recordTaskComplete(timer, "eval-timer", "report");
                }
            }, 0);
            assertTrue(reminderRan.await(10, java.util.concurrent.TimeUnit.SECONDS));

            assertTrue(detector.analyze().hasIssues(),
                    "the reminder fell due while the report still held the timer thread, and waited "
                            + "for it. Report: " + detector.analyze());
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("timer: a slow task with nothing falling due behind it stays silent (true negative)")
    void timerDetectorStaysSilentOnASlowTaskThatStarvesNobody() throws InterruptedException {
        TimerDetector detector = new TimerDetector();
        java.util.Timer timer = new java.util.Timer("eval-alone", true);
        try {
            detector.registerTimer(timer, "eval-timer");
            CountDownLatch done = new CountDownLatch(1);
            timer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    detector.recordTaskRun(timer, "eval-timer", this, "report");
                    // The same slow work, standing in for a GC pause too: longer than the 100 ms
                    // the detector used to call long-running, with no other task scheduled.
                    holdTimerThreadUntilPast(System.currentTimeMillis() + 150);
                    detector.recordTaskComplete(timer, "eval-timer", "report");
                    done.countDown();
                }
            }, 0);
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));

            assertFalse(detector.analyze().hasIssues(),
                    "no task fell due while the report ran, so none waited for it; a duration is not "
                            + "starvation (#575). Report: " + detector.analyze());
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("condition variable, lock registered: a consumer parked on the condition nobody signalled fires from the lock (true positive)")
    void conditionVariableWithItsLockFiresOnAConsumerTheLockShowsParked() throws InterruptedException {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        java.util.concurrent.locks.Condition notFull = lock.newCondition();
        detector.registerCondition(lock, notEmpty, "not-empty");
        detector.registerCondition(lock, notFull, "not-full");
        boolean[] ready = {false};

        Thread consumer = parkedConsumer(detector, lock, notEmpty, ready);
        lock.lock();
        try {
            ready[0] = true;
            detector.recordSignal(notFull, "not-full", false);   // the bug: nobody waits on notFull
            notFull.signal();
        } finally {
            lock.unlock();
        }

        try {
            var report = detector.analyze();
            assertTrue(report.hasIssues() && report.toString().contains("read from the lock"),
                    "the lock shows the consumer parked on not-empty, which nobody signalled (#592). "
                            + "Report:\n" + report);
        } finally {
            consumer.interrupt();
            consumer.join();
        }
    }

    @Test
    @DisplayName("condition variable, lock registered: an await the body recorded but never parked in stays silent (true negative)")
    void conditionVariableWithItsLockStaysSilentOnARecordedAwaitNobodyParkedIn() throws Exception {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        detector.registerCondition(lock, notEmpty, "not-empty");

        // The consumer records its await, then finds the item already there and never calls
        // await(): with the condition registered alone this read as a stuck waiter.
        Thread consumer = new Thread(() -> detector.recordAwait(notEmpty, "not-empty"));
        consumer.start();
        consumer.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "no thread is parked on the condition; the lock, not the recording, decides a stuck "
                        + "waiter (#592). Report:\n" + report);
    }

    /** A party that returns early on one path; the fixed twin arrives in finally (#602). */
    private static void phaserParty(PhaserDetector detector, java.util.concurrent.Phaser phaser,
                                    boolean earlyReturn, boolean arriveInFinally) {
        try {
            if (earlyReturn) {
                return;
            }
        } finally {
            if (arriveInFinally || !earlyReturn) {
                detector.recordAwaitAdvanceStarted(phaser);
                detector.recordAwaitAdvanceReturned(phaser, phaser.arriveAndAwaitAdvance());
            }
        }
    }

    private static Thread parkedPhaserParty(PhaserDetector detector, java.util.concurrent.Phaser phaser)
            throws InterruptedException {
        Thread waiter = new Thread(() -> phaserParty(detector, phaser, false, false), "waiting-party");
        // arriveAndAwaitAdvance ignores interrupts: a stranded party can only be abandoned.
        waiter.setDaemon(true);
        waiter.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!(phaser.getArrivedParties() == 1 && waiter.getState() == Thread.State.WAITING)) {
            assertTrue(System.nanoTime() < deadline, "the waiting party never parked");
            Thread.sleep(5);
        }
        return waiter;
    }

    @Test
    @DisplayName("phaser: a party's early return strands the other in arriveAndAwaitAdvance (true positive)")
    void phaserFiresWhenAPartyReturnsEarlyWithoutArriving() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        java.util.concurrent.Phaser phase = new java.util.concurrent.Phaser(2);
        detector.registerPhaser(phase, "phase", 2);

        parkedPhaserParty(detector, phase);
        phaserParty(detector, phase, true, false);   // the bug: the early return skips the arrival

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the waiting party never returned and phase 0 still has a party not arrived, with no "
                        + "timed wait recorded (#602). Report:\n" + report);
    }

    @Test
    @DisplayName("phaser: the twin that arrives in finally releases the waiter and stays silent (true negative)")
    void phaserStaysSilentWhenTheEarlyReturnArrivesInFinally() throws InterruptedException {
        PhaserDetector detector = new PhaserDetector();
        java.util.concurrent.Phaser phase = new java.util.concurrent.Phaser(2);
        detector.registerPhaser(phase, "phase", 2);

        Thread waiter = parkedPhaserParty(detector, phase);
        phaserParty(detector, phase, true, true);
        waiter.join(10_000);
        assertFalse(waiter.isAlive(), "both parties arrived, so the phase advanced");

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "every wait returned (#602). Report:\n" + report);
    }

    @Test
    @DisplayName("wakeup: a deadline loop that records its give-up after the last timed wait stays silent (true negative)")
    void wakeupStaysSilentWhenADeadlineLoopGivesUp() throws InterruptedException {
        WakeupDetector detector = new WakeupDetector();
        Object monitor = new Object();
        boolean[] gaveUp = {false};
        Thread consumer = new Thread(() -> {
            synchronized (monitor) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20);
                while (true) {   // nobody ever sets the condition
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        detector.recordGaveUp(monitor);
                        gaveUp[0] = true;
                        return;
                    }
                    detector.recordWaitEnter(monitor);
                    try {
                        monitor.wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    detector.recordWaitExit(monitor, false);
                }
            }
        });
        consumer.setDaemon(true);
        consumer.start();
        consumer.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(10));
        assertFalse(consumer.isAlive(), "consumer did not finish");

        assertTrue(gaveUp[0], "premise: the loop gave up at its deadline");
        var report = detector.analyzeWakeups();
        assertFalse(report.hasIssues(),
                "the last timed wait ran out and the thread returned without acting on the "
                        + "condition; the recorded give-up closes that return (#607). Report:\n" + report);
    }
}
