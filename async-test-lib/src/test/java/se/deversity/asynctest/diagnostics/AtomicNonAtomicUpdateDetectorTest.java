package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

public class AtomicNonAtomicUpdateDetectorTest {

    /** Runs a woven get then set of {@code atomic} on its own thread and waits for it (#1157). */
    private static void readThenStoreOnANewThread(AtomicNonAtomicUpdateDetector d, Object atomic)
            throws InterruptedException {
        Thread thread = new Thread(() -> {
            d.agentRead(atomic);
            d.agentStore(atomic);
        });
        thread.start();
        thread.join();
    }

    @Test
    void wovenGetThenSetFromTwoThreadsOfARoundIsReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        readThenStoreOnANewThread(d, counter);
        readThenStoreOnANewThread(d, counter);
        assertTrue(d.analyze().hasIssues(),
                "two threads read then stored with nothing in common held, so an update can be lost");
    }

    @Test
    void aWovenPairIsAPromptAndARecordedPairStaysAVerdict() throws InterruptedException {
        var woven = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        readThenStoreOnANewThread(woven, counter);
        readThenStoreOnANewThread(woven, counter);
        assertEquals(List.of(TrustTier.PROMPT),
                woven.analyze().grades().stream().map(GradedFindings.Grade::tier).toList(),
                "the agent sees calls and not values, so a woven pair cannot claim a verdict");

        var recorded = new AtomicNonAtomicUpdateDetector();
        recorded.recordGet(counter, "counter", Thread.currentThread());
        recorded.recordSet(counter, "counter", Thread.currentThread());
        assertEquals(List.of(TrustTier.VERDICT),
                recorded.analyze().grades().stream().map(GradedFindings.Grade::tier).toList(),
                "the test's own record call is what says the update was compound");
    }

    /** A woven get then store of {@code stored} on a reference, on its own thread (#1174). */
    private static void readThenStoreReference(AtomicNonAtomicUpdateDetector d, Object reference,
                                               Object stored) throws InterruptedException {
        Thread thread = new Thread(() -> {
            d.agentRead(reference);
            d.agentReferenceStore(reference, stored);
        });
        thread.start();
        thread.join();
    }

    @Test
    void aReferenceStoredFromItsOwnReadByTwoThreadsIsReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        Object reference = new java.util.concurrent.atomic.AtomicReference<>();
        readThenStoreReference(d, reference, List.of("first"));
        readThenStoreReference(d, reference, List.of("second"));
        assertTrue(d.analyze().hasIssues(), "each store can overwrite the update the other built");
    }

    @Test
    void aStatusFlagSetAfterAReadIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        Object reference = new java.util.concurrent.atomic.AtomicReference<>();
        readThenStoreReference(d, reference, Thread.State.RUNNABLE);
        readThenStoreReference(d, reference, Thread.State.RUNNABLE);
        assertFalse(d.analyze().hasIssues(),
                "an enum is a status, which is how Jackson's drainStatus is kept: nothing built from "
                        + "the read is lost");
    }

    @Test
    void aReferenceClearedAfterAReadIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        Object reference = new java.util.concurrent.atomic.AtomicReference<>();
        readThenStoreReference(d, reference, null);
        readThenStoreReference(d, reference, null);
        assertFalse(d.analyze().hasIssues(), "null builds on nothing the read returned");
    }

    @Test
    void wovenGetThenSetFromOneWriterIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        readThenStoreOnANewThread(d, counter);
        Thread reader = new Thread(() -> d.agentRead(counter));
        reader.start();
        reader.join();
        assertFalse(d.analyze().hasIssues(), "one writer among readers loses nothing");
    }

    @Test
    void wovenGetThenSetByTwoThreadsInDifferentRoundsIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        readThenStoreOnANewThread(d, counter);
        d.markInvocationStart();
        readThenStoreOnANewThread(d, counter);
        assertFalse(d.analyze().hasIssues(), "the harness orders rounds, so neither can lose the other's");
    }

    /** Runs a woven get then set of {@code atomic} under {@code lock} on its own thread. */
    private static void readThenStoreUnderALock(AtomicNonAtomicUpdateDetector d, Object atomic,
                                                Object lock) throws InterruptedException {
        Thread thread = new Thread(() -> {
            HeldLocks.acquired(lock);
            try {
                d.agentRead(atomic);
                d.agentStore(atomic);
            } finally {
                HeldLocks.released(lock);
            }
        });
        thread.start();
        thread.join();
    }

    @Test
    void wovenGetThenSetUnderOneLockIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Object lock = new Object();
        readThenStoreUnderALock(d, counter, lock);
        readThenStoreUnderALock(d, counter, lock);
        assertFalse(d.analyze().hasIssues(), "one lock held across both pairs excludes them");
    }

    @Test
    void aLockFreeReadBesideALockedUpdateIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Object lock = new Object();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                d.agentRead(counter);
                HeldLocks.acquired(lock);
                try {
                    d.agentRead(counter);
                    d.agentStore(counter);
                } finally {
                    HeldLocks.released(lock);
                }
            });
            thread.start();
            thread.join();
        }
        assertFalse(d.analyze().hasIssues(),
                "the shape of a segment count: a lock-free read decides nothing, and the update "
                        + "reads and stores under the lock");
    }

    @Test
    void wovenGetThenSetUnderTwoLocksIsReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        readThenStoreUnderALock(d, counter, new Object());
        readThenStoreUnderALock(d, counter, new Object());
        assertTrue(d.analyze().hasIssues(), "two different locks exclude nothing");
    }

    @Test
    void aReadOutsideTheLockThenAStoreInsideItIsReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Object lock = new Object();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                d.agentRead(counter);
                HeldLocks.acquired(lock);
                try {
                    d.agentStore(counter);
                } finally {
                    HeldLocks.released(lock);
                }
            });
            thread.start();
            thread.join();
        }
        assertTrue(d.analyze().hasIssues(), "the value stored was read before the lock was taken");
    }

    @Test
    void wovenCompareAndSetEndsThePendingRead() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                d.agentRead(counter);
                d.agentSwap(counter);
                d.agentStore(counter);
            });
            thread.start();
            thread.join();
        }
        assertFalse(d.analyze().hasIssues(), "a CAS loop's read is spent by the CAS, not by a later reset");
    }

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new AtomicNonAtomicUpdateDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenCasUsed() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordCas(counter, "counter", t); // CAS clears the pending get
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenSetWithoutPriorGet() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        d.recordSet(counter, "counter", Thread.currentThread()); // set without prior get — not a violation
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsGetThenSet() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordSet(counter, "counter", t); // non-atomic update
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    @Test
    void testDetectsMultipleAtomics() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger ai = new AtomicInteger();
        AtomicLong    al = new AtomicLong();
        Thread t = Thread.currentThread();
        d.recordGet(ai, "ai", t);
        d.recordSet(ai, "ai", t);
        d.recordGet(al, "al", t);
        d.recordSet(al, "al", t);
        assertTrue(d.analyze().hasIssues());
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testCasAfterGetPreventsViolation() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordCas(counter, "counter", t); // successful CAS — clears pending get
        d.recordSet(counter, "counter", t); // subsequent set has no pending get to flag
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new AtomicNonAtomicUpdateDetector();
        assertDoesNotThrow(() -> {
            d.recordGet(null, "x", Thread.currentThread());
            d.recordSet(null, "x", Thread.currentThread());
            d.recordCas(null, "x", Thread.currentThread());
            AtomicInteger ai = new AtomicInteger();
            d.recordGet(ai, "x", null);
            d.recordSet(ai, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordSet(counter, "counter", t);
        String s = d.analyze().toString();
        assertTrue(s.contains("ATOMIC NON-ATOMIC UPDATE"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("compareAndSet"));
    }

    /** Runs {@code step} on its own thread and waits for it. */
    private static void onAnotherThread(Runnable step) throws InterruptedException {
        Thread thread = new Thread(step);
        thread.start();
        thread.join();
    }

    @Test
    void aGetThenAnIncrementWithAnotherThreadsWriteBetweenIsReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger admitted = new AtomicInteger();
        d.agentRead(admitted);
        onAnotherThread(() -> d.agentIncrement(admitted));
        d.agentIncrement(admitted);

        var report = d.analyze();
        assertTrue(report.hasIssues(),
                "this thread checked the atomic, another thread wrote it, and this thread then "
                        + "incremented it on the strength of a stale check (#1249)");
        assertEquals(List.of(TrustTier.PROMPT),
                report.grades().stream().map(GradedFindings.Grade::tier).toList(),
                "the agent cannot see that the increment depended on the check, so it is a prompt");
    }

    @Test
    void aGetThenAnIncrementWithNoWriteBetweenIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger admitted = new AtomicInteger();
        d.agentRead(admitted);
        d.agentIncrement(admitted);
        onAnotherThread(() -> {
            d.agentRead(admitted);
            d.agentIncrement(admitted);
        });
        assertFalse(d.analyze().hasIssues(),
                "no write landed between either thread's check and its increment");
    }

    @Test
    void aStandaloneIncrementIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicLong hits = new AtomicLong();
        d.agentIncrement(hits);
        onAnotherThread(() -> d.agentIncrement(hits));
        d.agentIncrement(hits);
        assertFalse(d.analyze().hasIssues(), "an increment no check preceded is one atomic update");
    }

    @Test
    void aGetThenCompareAndSetLoopIsCorrect() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger admitted = new AtomicInteger();
        d.agentRead(admitted);
        onAnotherThread(() -> d.agentIncrement(admitted));
        d.agentSwap(admitted); // the compareAndSet that failed
        d.agentRead(admitted);
        d.agentSwap(admitted); // the retry that won
        assertFalse(d.analyze().hasIssues(), "a CAS loop re-checks after every lost race");
    }

    @Test
    void aWriteBetweenTheCheckAndTheIncrementInAnotherRoundIsNotCounted() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger admitted = new AtomicInteger();
        d.agentRead(admitted);
        d.markInvocationStart();
        onAnotherThread(() -> d.agentIncrement(admitted));
        d.agentIncrement(admitted);
        assertFalse(d.analyze().hasIssues(),
                "a check left pending when a round ended must not pair with the next round's "
                        + "increment, which the runner orders after it");
    }

    @Test
    void aCheckAndIncrementUnderALockHeldAcrossBothIsNotReported() throws InterruptedException {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger admitted = new AtomicInteger();
        Object lock = new Object();
        HeldLocks.acquired(lock); // what the agent records for a woven monitor enter
        try {
            d.agentRead(admitted);
            onAnotherThread(() -> d.agentIncrement(admitted));
            d.agentIncrement(admitted);
        } finally {
            HeldLocks.released(lock);
        }
        assertFalse(d.analyze().hasIssues(),
                "a lock held across the check and the increment is how bounded admission is "
                        + "usually guarded; the writer that skipped it is a different finding");
    }

    @Test
    void getThenSetUnderTheAtomicsOwnMonitorIsNotALostUpdate() throws Exception {
        AtomicNonAtomicUpdateDetector d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Runnable body = () -> {
            for (int i = 0; i < 100; i++) {
                synchronized (counter) {
                    d.recordGet(counter, "counter", Thread.currentThread());
                    counter.set(counter.get() + 1);
                    d.recordSet(counter, "counter", Thread.currentThread());
                }
            }
        };
        Thread a = new Thread(body, "guarded-a");
        Thread b = new Thread(body, "guarded-b");
        a.start(); b.start(); a.join(); b.join();
        assertFalse(d.analyze().hasIssues(),
            "get+set inside synchronized(counter) is mutually excluded and cannot lose an update; "
                + "a VERDICT-tier detector must consult the lockset like its siblings: " + d.analyze());
    }

    @Test
    void aGetInOneRoundAndASetInTheNextAreNotPaired() {
        AtomicNonAtomicUpdateDetector d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.markInvocationStart();          // the runner orders rounds; a pool thread is reused across them
        d.recordSet(counter, "counter", t);
        assertFalse(d.analyze().hasIssues(),
            "a pending get must not survive the round boundary on a reused pool thread");
    }

    // ---- The lock is judged within one round ---------------------------------------------------
    //
    // The runner finishes one round before it starts the next, so a lock that guarded every
    // get+set of one round says nothing about the next round's. These drive the detector through
    // an installed context, the way a run does, so the rounds are the runner's.

    private static se.deversity.asynctest.AsyncTestContext updateContext() {
        return new se.deversity.asynctest.AsyncTestContext(
            se.deversity.asynctest.AsyncTestConfig.builder().detectAtomicNonAtomicUpdates(true).build());
    }

    /** Starts one worker per body, each with {@code ctx} installed, and waits for all of them. */
    private static void runWorkers(se.deversity.asynctest.AsyncTestContext ctx, Runnable... bodies)
            throws InterruptedException {
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                se.deversity.asynctest.AsyncTestContext.install(ctx);
                try {
                    body.run();
                } finally {
                    se.deversity.asynctest.AsyncTestContext.uninstall();
                }
            }, "worker-" + i);
        }
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join();
    }

    /** A get+set on {@code counter} inside {@code synchronized (lock)}, with the lock declared. */
    private static Runnable getThenSetUnder(Object lock, AtomicInteger counter) {
        return () -> {
            synchronized (lock) {
                try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                    var d = se.deversity.asynctest.AsyncTestContext.atomicNonAtomicUpdateDetector();
                    int v = counter.get();
                    d.recordGet(counter, "counter", Thread.currentThread());
                    counter.set(v + 1);
                    d.recordSet(counter, "counter", Thread.currentThread());
                }
            }
        };
    }

    private static AtomicNonAtomicUpdateDetector detectorOf(se.deversity.asynctest.AsyncTestContext ctx) {
        se.deversity.asynctest.AsyncTestContext.install(ctx);
        try {
            return se.deversity.asynctest.AsyncTestContext.atomicNonAtomicUpdateDetector();
        } finally {
            se.deversity.asynctest.AsyncTestContext.uninstall();
        }
    }

    @Test
    void aDifferentLockInEachRoundIsNotInconsistentLocking() throws Exception {
        var ctx = updateContext();
        AtomicInteger counter = new AtomicInteger();
        for (Object lock : new Object[] {new Object(), new Object()}) {
            ctx.markInvocationStart();
            runWorkers(ctx, getThenSetUnder(lock, counter), getThenSetUnder(lock, counter));
        }
        var report = detectorOf(ctx).analyze();
        assertFalse(report.hasIssues(),
            "each round guarded every get+set with one lock; nothing crosses the round boundary: "
                + report);
    }

    @Test
    void twoThreadsInOneRoundUnderDifferentLocksStillFire() throws Exception {
        var ctx = updateContext();
        AtomicInteger counter = new AtomicInteger();
        ctx.markInvocationStart();
        runWorkers(ctx, getThenSetUnder(new Object(), counter), getThenSetUnder(new Object(), counter));
        assertTrue(detectorOf(ctx).analyze().hasIssues(),
            "two locks in one round exclude nothing from each other");
    }

    // ---- An ordered hand-off inside one round (#792) -------------------------------------------
    //
    // SelfGuard starts its lockset again where the happens-before model orders a thread's access
    // after the previous owner's (#746), and sawUnguardedRound() reads that lockset. The edge
    // orders every get and set before it ahead of every one after it, so no set can land between
    // a get and its set on the other side: one lock before the hand-off and another after it lose
    // no update. With no edge the model sees, the same two locks are two locks in one round.

    /**
     * {@return whether a get+set under one lock, then a get+set under another after a latch,
     * reports}
     *
     * @param ordered whether the latch goes through the woven hooks, the edge the model sees
     */
    private static boolean handOffBetweenTwoLocksReported(boolean ordered) throws Exception {
        var ctx = updateContext();
        AtomicInteger counter = new AtomicInteger();
        var handedOver = new java.util.concurrent.CountDownLatch(1);
        Runnable first = getThenSetUnder(new Object(), counter);
        Runnable second = getThenSetUnder(new Object(), counter);
        ctx.markInvocationStart();
        runWorkers(ctx, () -> {
            first.run();
            if (ordered) {
                se.deversity.asynctest.AgentConcurrencyUtilHooks.countDown(handedOver);
            } else {
                handedOver.countDown();
            }
        }, () -> {
            try {
                if (ordered) {
                    se.deversity.asynctest.AgentConcurrencyUtilHooks.await(handedOver);
                } else {
                    handedOver.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            second.run();
        });
        return detectorOf(ctx).analyze().hasIssues();
    }

    @Test
    void aDifferentLockOnEachSideOfAnOrderedHandOffIsNotALostUpdate() throws Exception {
        assertFalse(handOffBetweenTwoLocksReported(true),
            "every get+set before the hand-off happens before every one after it");
    }

    @Test
    void aDifferentLockOnEachSideOfAHandOffTheModelNeverSawStillFires() throws Exception {
        assertTrue(handOffBetweenTwoLocksReported(false),
            "nothing orders the second get+set after the first, and their locks differ");
    }

    // ---- An owner whose clock dropped the hand-off (#821, #830) --------------------------------
    //
    // A clock keeps at most HappensBefore.MAX_ENTRIES threads. B takes the counter over from A,
    // then learns of more threads than that, so its clock no longer knows A. B's next get+set
    // still follows the hand-off in program order, after the one that took the counter over, so
    // its lockset is judged from the hand-off, as SharedMessageDigestDetectorTest pins for the
    // digest. Checked against B's clock instead, it fell back to the whole round and read A's
    // lock and B's as two locks in one round.

    /**
     * {@return whether A's get+set under one lock, then B's under another after an ordered
     * hand-off, then B's again once B's clock dropped A, reports}
     *
     * @param guardedLater whether B's later get+set holds the lock B took the counter over under,
     *                     rather than none
     */
    private static boolean droppedClockReported(boolean guardedLater) throws Exception {
        var ctx = updateContext();
        AtomicInteger counter = new AtomicInteger();
        var handedOver = new java.util.concurrent.CountDownLatch(1);
        var aThread = new AtomicLong();
        var forgotA = new java.util.concurrent.atomic.AtomicBoolean();
        Object bLock = new Object();
        Runnable first = getThenSetUnder(new Object(), counter);
        Runnable bTakes = getThenSetUnder(bLock, counter);
        Runnable bLater = guardedLater ? getThenSetUnder(bLock, counter) : () -> {
            var d = se.deversity.asynctest.AsyncTestContext.atomicNonAtomicUpdateDetector();
            int v = counter.get();
            d.recordGet(counter, "counter", Thread.currentThread());
            counter.set(v + 1);
            d.recordSet(counter, "counter", Thread.currentThread());
        };
        ctx.markInvocationStart();
        runWorkers(ctx, () -> {
            aThread.set(Thread.currentThread().threadId());
            first.run();
            se.deversity.asynctest.AgentConcurrencyUtilHooks.countDown(handedOver);
        }, () -> {
            try {
                se.deversity.asynctest.AgentConcurrencyUtilHooks.await(handedOver);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            bTakes.run();
            SharedMessageDigestDetectorTest.learnOfMoreThreadsThanAClockKeeps();
            forgotA.set(HappensBefore.current().countOf(aThread.get()) == 0);
            bLater.run();
        });
        assertTrue(forgotA.get(), "precondition: B's clock dropped A");
        return detectorOf(ctx).analyze().hasIssues();
    }

    @Test
    void anOwnerWhoseClockDroppedTheHandOffStillJudgesFromIt() throws Exception {
        assertFalse(droppedClockReported(true),
            "every get+set of B's holds B's lock and follows A's in program order after the hand-off");
    }

    @Test
    void anUnguardedGetThenSetByAnOwnerWhoseClockDroppedTheHandOffStillFires() throws Exception {
        assertTrue(droppedClockReported(false),
            "B's later get+set holds no lock; the hand-off excuses A's lock, not a missing one");
    }

    /** The detector releases a subject the code under test dropped (#918). */
    @Test
    void doesNotKeepARecordedSubjectAlive() throws InterruptedException {
        AtomicNonAtomicUpdateDetector d = new AtomicNonAtomicUpdateDetector();
        AbstractInstanceDetectorTest.assertNotRetained(Object::new, a -> d.recordGet(a, null, Thread.currentThread()));
    }
}
