package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

public class AtomicNonAtomicUpdateDetectorTest {

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
}
