package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.diagnostics.AtomicNonAtomicUpdateDetector;
import se.deversity.asynctest.diagnostics.AtomicityValidator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that the runner resets the atomicity epoch before every round.
 *
 * <p>{@code AtomicityValidator} refuses to pair accesses from different rounds, because the
 * runner's latch and submissions order rounds totally and a cross-round pair cannot race. That
 * refusal works through {@code markInvocationStart}, which the validator unit-tests
 * ({@code crossRoundAccessesOrderedByTheHarnessAreNotFlagged}) and which the runner has to call
 * at the top of each round. PIT's 2026-08-31 baseline showed the runner-side call surviving
 * deletion (#426): remove it and every cross-round pair becomes a false positive, and nothing
 * noticed.
 *
 * <p>The fixture records exactly one write in round one and one read in round two, by two
 * different recorded thread ids, and nothing else. With the reset in place the validator sees
 * one access per epoch and stays silent; with it deleted both land in one epoch and it reports.
 * The positive control records the same two accesses inside one round, which must be reported,
 * so a silent run cannot pass for want of any recording at all.
 */
@E2E
class ConcurrencyRunnerInvocationEpochBindingTest {

    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    /** One write in round one, one read in round two, and nothing else. */
    public static class CrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 2, detectAtomicityViolations = true)
        void body() {
            // Rounds are totally ordered by the runner, so the first two executions are round
            // one and the next two are round two. Thread ids are passed explicitly so the
            // pairing does not depend on whether the pool handed out the same thread twice.
            int n = EXECUTIONS.getAndIncrement();
            AtomicityValidator validator = AsyncTestContext.get().sharedAtomicityValidator();
            if (n == 0) {
                validator.recordFieldAccess("handoff", 1, true, 101L);
            } else if (n == 2) {
                validator.recordFieldAccess("handoff", 1, false, 202L);
            }
        }
    }

    /** The same write and read, both in round one: the pair the validator must report. */
    public static class SameRound {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 2, detectAtomicityViolations = true)
        void body() {
            int n = EXECUTIONS.getAndIncrement();
            AtomicityValidator validator = AsyncTestContext.get().sharedAtomicityValidator();
            if (n == 0) {
                validator.recordFieldAccess("handoff", 1, true, 101L);
            } else if (n == 1) {
                validator.recordFieldAccess("handoff", 1, false, 202L);
            }
        }
    }

    @Test
    @DisplayName("a write in one round and a read in the next are not paired, because the runner resets the epoch")
    void crossRoundAccessesAreNotPaired() {
        run(CrossRoundOnly.class);
        assertFalse(REPORTS.containsKey("AtomicityValidator"),
                "one write in round one and one read in round two were reported as an atomicity "
                        + "violation. Rounds are totally ordered by the runner, so this pair cannot "
                        + "race; the validator only knows that because ConcurrencyRunner calls "
                        + "markInvocationStart before each round (#426). Report: "
                        + REPORTS.get("AtomicityValidator"));
    }

    @Test
    @DisplayName("the same write and read inside one round are paired, so the silence above is not vacuous")
    void sameRoundAccessesArePaired() {
        run(SameRound.class);
        assertTrue(REPORTS.containsKey("AtomicityValidator"),
                "a write and a read by two threads in the same round must be reported; if this "
                        + "is silent the recording never reached the validator and the cross-round "
                        + "test proves nothing. Reports: " + REPORTS.keySet());
    }

    public static class AtomicCrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final AtomicInteger SUBJECT = new AtomicInteger();

        // One platform worker, so the same pool thread runs both rounds: a get left pending in
        // round one would pair with the set in round two unless the runner resets the epoch.
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAtomicNonAtomicUpdates = true)
        void body() {
            int n = EXECUTIONS.getAndIncrement();
            AtomicNonAtomicUpdateDetector d = AsyncTestContext.atomicNonAtomicUpdateDetector();
            if (n == 0) {
                d.recordGet(SUBJECT, "subject", Thread.currentThread());
            } else {
                d.recordSet(SUBJECT, "subject", Thread.currentThread());
            }
        }
    }

    public static class AtomicSameRound {
        static final AtomicInteger SUBJECT = new AtomicInteger();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAtomicNonAtomicUpdates = true)
        void body() {
            AtomicNonAtomicUpdateDetector d = AsyncTestContext.atomicNonAtomicUpdateDetector();
            d.recordGet(SUBJECT, "subject", Thread.currentThread());
            d.recordSet(SUBJECT, "subject", Thread.currentThread());
        }
    }

    @Test
    @DisplayName("a get in one round and a set in the next are not paired by AtomicNonAtomicUpdateDetector")
    void atomicCrossRoundAccessesAreNotPaired() {
        run(AtomicCrossRoundOnly.class);
        assertFalse(REPORTS.containsKey("AtomicNonAtomicUpdateDetector"),
                "the pending get survived the round boundary on the reused pool thread; "
                        + "AsyncTestContext.markInvocationStart must reach this detector. Report: "
                        + REPORTS.get("AtomicNonAtomicUpdateDetector"));
    }

    @Test
    @DisplayName("the same get and set inside one round are paired, so the silence above is not vacuous")
    void atomicSameRoundAccessesArePaired() {
        run(AtomicSameRound.class);
        assertTrue(REPORTS.containsKey("AtomicNonAtomicUpdateDetector"),
                "a get followed by a set on one thread in one round must be reported: " + REPORTS.keySet());
    }

    /** Cancel in round one, an untouched pipeline running to completion in round two. */
    public static class CfCrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectCompletableFutureCancellationPropagation = true)
        void body() {
            int n = EXECUTIONS.getAndIncrement();
            var d = AsyncTestContext.cfCancellationPropagationDetector();
            if (n == 0) {
                d.cancel(new java.util.concurrent.CompletableFuture<String>(), "report", "view", false);
            } else {
                d.recordWorkStarted("report", "fetch", Thread.currentThread());
                d.recordWorkCompleted("report", "fetch", Thread.currentThread());
            }
        }
    }

    /** The same cancel and completion inside one round: the pair that must still be reported. */
    public static class CfSameRound {
        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectCompletableFutureCancellationPropagation = true)
        void body() {
            var d = AsyncTestContext.cfCancellationPropagationDetector();
            d.cancel(new java.util.concurrent.CompletableFuture<String>(), "report", "view", false);
            d.recordWorkStarted("report", "fetch", Thread.currentThread());
            d.recordWorkCompleted("report", "fetch", Thread.currentThread());
        }
    }

    @Test
    @DisplayName("a cancel in one round does not convict a stage that completed in the next")
    void cfCrossRoundCompletionIsNotReported() {
        run(CfCrossRoundOnly.class);
        String report = REPORTS.get("CompletableFutureCancellationPropagationDetector");
        assertTrue(report == null || !report.contains("ran to completion"),
                "round two's stage was matched against round one's cancel. The runner orders "
                        + "rounds, so it cannot be downstream of that cancel; the detector only "
                        + "knows that because AsyncTestContext.markInvocationStart reaches it "
                        + "(#495). Report: " + report);
    }

    @Test
    @DisplayName("the same cancel and completion inside one round are reported, so the silence above is not vacuous")
    void cfSameRoundCompletionIsReported() {
        run(CfSameRound.class);
        String report = REPORTS.get("CompletableFutureCancellationPropagationDetector");
        assertTrue(report != null && report.contains("ran to completion"),
                "a stage completing after a cancel in the same round must be reported; if this is "
                        + "silent the recording never reached the detector and the cross-round test "
                        + "proves nothing. Reports: " + REPORTS.keySet() + " -> " + report);
    }

    /** A supplier that throws in round one, and a well-behaved one in round two. */
    public static class LazyConstantCrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        // One platform worker, so the same pool thread runs both rounds: the entry a thrown
        // supplier leaves behind follows that thread into round two.
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectLazyConstantMisuse = true)
        void body() {
            var d = AsyncTestContext.lazyConstantMisuseDetector();
            if (EXECUTIONS.getAndIncrement() == 0) {
                // The supplier throws, and the caller had no finally, so no recordComputeEnd.
                d.recordComputeStart("CONFIG", Thread.currentThread());
            } else {
                d.recordComputeStart("CONFIG", Thread.currentThread());
                d.recordComputeEnd("CONFIG", Thread.currentThread(), "value");
            }
        }
    }

    /** Both starts inside one round: the genuine re-entry that must still be reported. */
    public static class LazyConstantSameRound {
        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAll = false, detectLazyConstantMisuse = true)
        void body() {
            var d = AsyncTestContext.lazyConstantMisuseDetector();
            d.recordComputeStart("CONFIG", Thread.currentThread());
            d.recordComputeStart("CONFIG", Thread.currentThread());
            d.recordComputeEnd("CONFIG", Thread.currentThread(), "value");
        }
    }

    @Test
    @DisplayName("a supplier abandoned in one round is not re-entrancy in the next")
    void lazyConstantCrossRoundComputeIsNotReported() {
        run(LazyConstantCrossRoundOnly.class);
        String report = REPORTS.get("LazyConstantMisuseDetector");
        assertTrue(report == null || !report.contains("re-entered"),
                "the entry a thrown supplier left in flight followed the pooled worker into "
                        + "round two. AsyncTestContext.markInvocationStart must reach this "
                        + "detector (#498). Report: " + report);
    }

    @Test
    @DisplayName("two starts inside one round are re-entrancy, so the silence above is not vacuous")
    void lazyConstantSameRoundComputeIsReported() {
        run(LazyConstantSameRound.class);
        String report = REPORTS.get("LazyConstantMisuseDetector");
        assertTrue(report != null && report.contains("re-entered"),
                "a supplier entering itself inside one round must be reported; if this is silent "
                        + "the recording never reached the detector. Reports: " + REPORTS.keySet()
                        + " -> " + report);
    }

    /** A lost notify and an if-wait in round one; the same pooled thread tests again in round two. */
    public static class MissedSignalCheckInNextRound {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final Object MONITOR = new Object();

        // One platform worker, so the same pool thread runs both rounds: round two's check would
        // read as a re-test of round one's wait unless the runner closes the round (#635).
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectMissedSignals = true)
        void body() {
            var d = AsyncTestContext.missedSignalDetector();
            if (EXECUTIONS.getAndIncrement() == 0) {
                d.recordNotify(MONITOR); // nobody waiting: lost
                d.recordWait(MONITOR);
                d.recordWakeup(MONITOR); // the timed wait ran out, unsignalled
            } else {
                d.recordPredicateCheck(MONITOR, true);
            }
        }
    }

    /** The same history with the check inside round one, after the wakeup: a guarded loop. */
    public static class MissedSignalCheckInSameRound {
        static final Object MONITOR = new Object();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAll = false, detectMissedSignals = true)
        void body() {
            var d = AsyncTestContext.missedSignalDetector();
            d.recordNotify(MONITOR);
            d.recordWait(MONITOR);
            d.recordWakeup(MONITOR);
            d.recordPredicateCheck(MONITOR, true);
        }
    }

    @Test
    @DisplayName("a predicate check in the next round does not guard the wait of the round before")
    void missedSignalCheckInNextRoundDoesNotGuard() {
        run(MissedSignalCheckInNextRound.class);
        String report = REPORTS.get("MissedSignalDetector");
        assertTrue(report != null && report.contains("SIGNAL LOST"),
                "round two's check was taken as a re-test of round one's unsignalled wait. "
                        + "AsyncTestContext.markInvocationStart must reach this detector (#635). "
                        + "Reports: " + REPORTS.keySet() + " -> " + report);
    }

    @Test
    @DisplayName("a check after the wakeup in the same round guards it, so the report above is not the recording alone")
    void missedSignalCheckInSameRoundGuards() {
        run(MissedSignalCheckInSameRound.class);
        String report = REPORTS.get("MissedSignalDetector");
        assertTrue(report == null || !report.contains("SIGNAL LOST"),
                "a check after the wakeup in the same round re-tests the predicate and must "
                        + "silence the wait (#635). Report: " + report);
    }

    /** An await round one never exits, then a complete signalled wait in round two, on one thread. */
    public static class ConditionAwaitAbandonedCrossRound {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final java.util.concurrent.locks.Condition CONDITION =
                new java.util.concurrent.locks.ReentrantLock().newCondition();

        // One platform worker, so the same pool thread runs both rounds: without the round
        // boundary, its await in round two is merged into round one's and that one vanishes.
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectConditionVariableIssues = true)
        void body() {
            var d = AsyncTestContext.conditionVariableDetector();
            USED.set(d);
            d.registerCondition(CONDITION, "cross-round");
            d.recordAwait(CONDITION, "cross-round");
            if (EXECUTIONS.getAndIncrement() == 0) {
                return;   // round one ends inside the wait, with no exit recorded
            }
            d.recordSignal(CONDITION, "cross-round", false);
            d.recordAwaitExit(CONDITION, "cross-round", false);
        }
    }

    /** A while loop that awaits twice before its one recorded exit, in each of two rounds. */
    public static class ConditionLoopReAwaitEachRound {
        static final java.util.concurrent.locks.Condition CONDITION =
                new java.util.concurrent.locks.ReentrantLock().newCondition();

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectConditionVariableIssues = true)
        void body() {
            var d = AsyncTestContext.conditionVariableDetector();
            USED.set(d);
            d.registerCondition(CONDITION, "loop");
            d.recordAwait(CONDITION, "loop");
            d.recordSignal(CONDITION, "loop", false);
            d.recordAwait(CONDITION, "loop");   // woken, predicate still false: waits again
            d.recordSignal(CONDITION, "loop", false);
            d.recordAwaitExit(CONDITION, "loop", false);
        }
    }

    /**
     * The detector the last condition fixture ran against. Since #666 an abandoned await on a
     * condition registered without its lock is a note, which never reaches a report listener, so
     * the binding is read from the detector's own analysis after the run.
     */
    /** The abandoned-await note; the activity line also names earlier rounds, so match the note. */
    private static final String ABANDONED = "recorded in an earlier round never exited";

    static final java.util.concurrent.atomic.AtomicReference<se.deversity.asynctest.diagnostics.ConditionVariableDetector>
            USED = new java.util.concurrent.atomic.AtomicReference<>();

    @Test
    @DisplayName("an await abandoned in round one is noted after the pooled thread awaits in round two")
    void conditionAwaitAbandonedCrossRoundIsNoted() {
        ConditionAwaitAbandonedCrossRound.EXECUTIONS.set(0);
        USED.set(null);
        run(ConditionAwaitAbandonedCrossRound.class);
        String report = USED.get() == null ? null : USED.get().analyze().toString();
        assertTrue(report != null && report.contains(ABANDONED),
                "round one's await never exited; round two's exit closes round two's await. "
                        + "AsyncTestContext.markInvocationStart must reach this detector (#593). "
                        + "Report: " + report);
    }

    @Test
    @DisplayName("a loop re-awaiting inside each round is one wait per round, so the note above is not the re-await alone")
    void conditionLoopReAwaitEachRoundIsSilent() {
        USED.set(null);
        run(ConditionLoopReAwaitEachRound.class);
        String report = USED.get() == null ? null : USED.get().analyze().toString();
        assertTrue(report != null && !report.contains(ABANDONED),
                "a while loop that awaits again before its single recorded exit abandons nothing. "
                        + "Report: " + report);
        assertFalse(REPORTS.containsKey("ConditionVariableDetector"),
                "Report: " + REPORTS.get("ConditionVariableDetector"));
    }

    /** A write release in round one and a read acquire in round two, on one pooled thread. */
    public static class LockDowngradeCrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        /** Shared with the same-round fixture so {@code writeOnce} names one subject. */
        static final java.util.concurrent.locks.ReentrantReadWriteLock LOCK =
                LockDowngradeSameRound.LOCK;

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectLockDowngrade = true)
        void body() {
            var d = AsyncTestContext.lockDowngradeDetector();
            if (EXECUTIONS.getAndIncrement() == 0) {
                LOCK.writeLock().lock();
                d.recordWriteLockAcquired(LOCK, "shared");
                LOCK.writeLock().unlock();
                d.recordWriteLockReleased(LOCK, "shared");
                // The writer inside the gap has to be another thread: a second write acquire by
                // this thread would close its own gap before anything could be concluded.
                writeOnce(d);
            } else {
                LOCK.readLock().lock();
                d.recordReadLockAcquired(LOCK, "shared");
                LOCK.readLock().unlock();
                d.recordReadLockReleased(LOCK, "shared");
            }
        }
    }

    @Test
    @DisplayName("a downgrade gap left open in one round is not closed by the next round's read")
    void lockDowngradeCrossRoundGapIsNotReported() {
        run(LockDowngradeCrossRoundOnly.class);
        String report = REPORTS.get("LockDowngradeDetector");
        assertTrue(report == null || !report.contains("unsafe"),
                "the write release and the read acquire are in different rounds, so nothing was "
                        + "downgraded. AsyncTestContext.markInvocationStart must reach this "
                        + "detector (#499). Report: " + report);
    }

    /** The same release-then-read, both inside one round: the shape that must still fire. */
    public static class LockDowngradeSameRound {
        static final java.util.concurrent.locks.ReentrantReadWriteLock LOCK =
                new java.util.concurrent.locks.ReentrantReadWriteLock();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAll = false, detectLockDowngrade = true)
        void body() {
            var d = AsyncTestContext.lockDowngradeDetector();
            LOCK.writeLock().lock();
            d.recordWriteLockAcquired(LOCK, "shared");
            LOCK.writeLock().unlock();
            d.recordWriteLockReleased(LOCK, "shared");
            writeOnce(d);
            LOCK.readLock().lock();
            d.recordReadLockAcquired(LOCK, "shared");
            LOCK.readLock().unlock();
            d.recordReadLockReleased(LOCK, "shared");
        }
    }

    @Test
    @DisplayName("the same release-then-read inside one round is reported, so the silence above is not vacuous")
    void lockDowngradeSameRoundGapIsReported() {
        run(LockDowngradeSameRound.class);
        String report = REPORTS.get("LockDowngradeDetector");
        assertTrue(report != null && report.contains("unsafe"),
                "a write released and a read taken back with another writer in between, inside "
                        + "one round, is the unsafe downgrade this detector names; if this is "
                        + "silent the recording never reached it. Reports: " + REPORTS.keySet()
                        + " -> " + report);
    }

    /** One write acquire and release by a thread other than the caller's. */
    private static void writeOnce(se.deversity.asynctest.diagnostics.LockDowngradeDetector d) {
        var lock = LockDowngradeSameRound.LOCK;
        Thread writer = new Thread(() -> {
            lock.writeLock().lock();
            d.recordWriteLockAcquired(lock, "shared");
            lock.writeLock().unlock();
            d.recordWriteLockReleased(lock, "shared");
        });
        writer.start();
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A validation that fails in round one, and a read lock on the same pooled thread in round two. */
    public static class StampedLockCrossRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final java.util.concurrent.locks.StampedLock LOCK =
                new java.util.concurrent.locks.StampedLock();

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectStampedLockIssues = true)
        void body() {
            var d = AsyncTestContext.stampedLockDetector();
            if (EXECUTIONS.getAndIncrement() == 0) {
                long stamp = LOCK.tryOptimisticRead();
                d.recordOptimisticRead(LOCK, "shared", stamp);
                // The stale value is used as read: no read lock, no retry, in this body.
                d.recordOptimisticValidation(LOCK, "shared", stamp, false);
            } else {
                long stamp = LOCK.readLock();
                d.recordReadLock(LOCK, "shared", stamp);
                LOCK.unlockRead(stamp);
                d.recordUnlock(LOCK, "shared", stamp);
            }
        }
    }

    /** The same failure followed by the read lock inside one round: the fallback that must stay silent. */
    public static class StampedLockSameRound {
        static final java.util.concurrent.locks.StampedLock LOCK =
                new java.util.concurrent.locks.StampedLock();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAll = false, detectStampedLockIssues = true)
        void body() {
            var d = AsyncTestContext.stampedLockDetector();
            long optimistic = LOCK.tryOptimisticRead();
            d.recordOptimisticRead(LOCK, "shared", optimistic);
            d.recordOptimisticValidation(LOCK, "shared", optimistic, false);
            long stamp = LOCK.readLock();
            d.recordReadLock(LOCK, "shared", stamp);
            LOCK.unlockRead(stamp);
            d.recordUnlock(LOCK, "shared", stamp);
        }
    }

    @Test
    @DisplayName("a failed validation in one round is not settled by the next round's read lock")
    void stampedLockCrossRoundFallbackIsNotAccepted() {
        run(StampedLockCrossRoundOnly.class);
        String report = REPORTS.get("StampedLockDetector");
        assertTrue(report != null && report.contains("followed by no read lock"),
                "the body that saw validate() fail finished without falling back, and the read "
                        + "lock was taken by the same pooled thread in the next round. "
                        + "AsyncTestContext.markInvocationStart must reach this detector (#588). "
                        + "Reports: " + REPORTS.keySet() + " -> " + report);
    }

    @Test
    @DisplayName("the same failure and read lock inside one round are the documented fallback, so the finding above is not the fallback's")
    void stampedLockSameRoundFallbackIsSilent() {
        run(StampedLockSameRound.class);
        assertFalse(REPORTS.containsKey("StampedLockDetector"),
                "validate() failing and a read lock taken in the same body is the StampedLock "
                        + "idiom: " + REPORTS.get("StampedLockDetector"));
    }

    /** An unsignalled wait return in round one, and a notified wait in round two, on one thread. */
    public static class WakeupCrossRound {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final Object MONITOR = new Object();

        // One platform worker, so the same pool thread runs both rounds: its wait in round two
        // would read as the re-check of round one's return unless the round boundary closes it.
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = false,
                   detectAll = false, detectWakeupIssues = true)
        void body() {
            var d = AsyncTestContext.wakeupDetector();
            d.recordWaitEnter(MONITOR);
            d.recordWaitExit(MONITOR, EXECUTIONS.getAndIncrement() != 0);
        }
    }

    /** The loop re-check inside one round: an unsignalled return followed by a second wait. */
    public static class WakeupSameRoundReWait {
        static final Object MONITOR = new Object();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = false,
                   detectAll = false, detectWakeupIssues = true)
        void body() {
            var d = AsyncTestContext.wakeupDetector();
            d.recordWaitEnter(MONITOR);
            d.recordWaitExit(MONITOR, false);
            d.recordWaitEnter(MONITOR);
            d.recordWaitExit(MONITOR, true);
        }
    }

    @Test
    @DisplayName("an unsignalled wait return in one round is not excused by the next round's wait")
    void wakeupCrossRoundReturnIsReported() {
        WakeupCrossRound.EXECUTIONS.set(0);
        run(WakeupCrossRound.class);
        assertTrue(REPORTS.containsKey("WakeupDetector"),
                "round one's waiter returned with no notify and its body ended without waiting "
                        + "again; the pooled thread's wait in round two is a new body execution. "
                        + "AsyncTestContext.markInvocationStart must reach this detector (#590). "
                        + "Reports: " + REPORTS.keySet());
    }

    @Test
    @DisplayName("the same return followed by a second wait inside one round is silent, so the finding above is not the return alone")
    void wakeupSameRoundReWaitIsSilent() {
        run(WakeupSameRoundReWait.class);
        assertFalse(REPORTS.containsKey("WakeupDetector"),
                "a return followed by another wait in the same body is the while loop's re-check. "
                        + "Report: " + REPORTS.get("WakeupDetector"));
    }

    /** Enables the pinning detector and does nothing else to start it. */
    public static class PinningEnabledOnly {
        static final AtomicInteger EVENTS_SEEN = new AtomicInteger(-1);

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = true,
                   detectAll = false, detectVirtualThreadPinning = true)
        void body() {
            // No startMonitoring() call. Until #501 the detector's monitoring flag defaulted to
            // false and nothing in main code turned it on, so recordPinningEvent returned early
            // and detectVirtualThreadPinning = true advertised a check that never ran.
            var detector = AsyncTestContext.virtualThreadPinningDetector();
            detector.recordPinningEvent(Thread.currentThread(), "Object.wait on a monitor");
            // Asserted on the recorded events rather than on hasIssues(): whether a cause still
            // pins depends on the running JDK (JEP 491), and the question here is only whether
            // the detector recorded anything at all.
            EVENTS_SEEN.set(detector.analyzePinning().getEvents().size());
        }
    }

    @Test
    @DisplayName("enabling the pinning detector is enough to make it record")
    void virtualThreadPinningDetectorIsNotInertWhenMerelyEnabled() {
        PinningEnabledOnly.EVENTS_SEEN.set(-1);
        run(PinningEnabledOnly.class);
        assertTrue(PinningEnabledOnly.EVENTS_SEEN.get() > 0,
                "a detector the config enabled has to be able to record. Constructing it and "
                        + "never starting it left recordPinningEvent returning early, so "
                        + "detectVirtualThreadPinning = true produced a clean report on code "
                        + "that pins (#501). Events recorded: "
                        + PinningEnabledOnly.EVENTS_SEEN.get());
    }

    private static void run(Class<?> fixture) {
        REPORTS.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTS.put(detectorName, report);
            }
        };
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        }
    }

    // ---- a thread set that spans rounds is not two threads at once ----
    //
    // Virtual threads are the default, and each body execution gets a fresh one, so with
    // threads = 1 a subject touched in two rounds is touched by two thread ids that never ran at
    // the same time: the runner joins round one before round two starts. Each detector below
    // used to count those ids over the whole run and report sharing; each pair is the cross-round
    // run that must stay silent and the same-round run that must still fire.

    /** One SecureRandom, one thread per round, two rounds. */
    public static class SecureRandomCrossRound {
        static final java.security.SecureRandom RNG = new java.security.SecureRandom();

        @AsyncTest(threads = 1, invocations = 2, detectAll = false, detectSharedSecureRandom = true)
        void body() {
            AsyncTestContext.sharedSecureRandomDetector().recordAccess(RNG, "rng", Thread.currentThread());
        }
    }

    /** The same SecureRandom touched by two threads inside one round. */
    public static class SecureRandomSameRound {
        static final java.security.SecureRandom RNG = new java.security.SecureRandom();

        @AsyncTest(threads = 2, invocations = 1, detectAll = false, detectSharedSecureRandom = true)
        void body() {
            AsyncTestContext.sharedSecureRandomDetector().recordAccess(RNG, "rng", Thread.currentThread());
        }
    }

    @Test
    @DisplayName("a SecureRandom used by one thread per round is not shared across threads")
    void secureRandomCrossRoundIsNotShared() {
        run(SecureRandomCrossRound.class);
        assertFalse(REPORTS.containsKey("SharedSecureRandomDetector"),
                "no two threads used the instance at once, so there is no contention and no "
                        + "concurrent access: " + REPORTS.get("SharedSecureRandomDetector"));
    }

    @Test
    @DisplayName("a SecureRandom used by two threads in one round is shared, so the silence above is not vacuous")
    void secureRandomSameRoundIsShared() {
        run(SecureRandomSameRound.class);
        assertTrue(REPORTS.containsKey("SharedSecureRandomDetector"), "Reports: " + REPORTS.keySet());
    }

    /** A once-flag CAS, failing on every attempt after the first, one thread per round. */
    public static class CasFlagCrossRound {
        static final java.util.concurrent.atomic.AtomicBoolean FLAG =
                new java.util.concurrent.atomic.AtomicBoolean();

        @AsyncTest(threads = 1, invocations = 2, detectAll = false, detectHighContentionAtomic = true)
        void body() {
            var d = AsyncTestContext.highContentionAtomicDetector();
            for (int i = 0; i < 600; i++) {
                d.recordCasAttempt(FLAG, FLAG.compareAndSet(false, true));
            }
        }
    }

    /** The same failing CAS from two threads inside one round. */
    public static class CasFlagSameRound {
        static final java.util.concurrent.atomic.AtomicBoolean FLAG =
                new java.util.concurrent.atomic.AtomicBoolean();

        @AsyncTest(threads = 2, invocations = 1, detectAll = false, detectHighContentionAtomic = true)
        void body() {
            var d = AsyncTestContext.highContentionAtomicDetector();
            for (int i = 0; i < 600; i++) {
                d.recordCasAttempt(FLAG, FLAG.compareAndSet(false, true));
            }
        }
    }

    @Test
    @DisplayName("CAS failures by one thread per round are not contention")
    void casFailuresCrossRoundAreNotContention() {
        run(CasFlagCrossRound.class);
        assertFalse(REPORTS.containsKey("HighContentionAtomicDetector"),
                "one thread at a time cannot contend with itself: "
                        + REPORTS.get("HighContentionAtomicDetector"));
    }

    @Test
    @DisplayName("the same CAS failures from two threads in one round are reported, so the silence above is not vacuous")
    void casFailuresSameRoundAreContention() {
        run(CasFlagSameRound.class);
        assertTrue(REPORTS.containsKey("HighContentionAtomicDetector"), "Reports: " + REPORTS.keySet());
    }

    /** A record exposing a mutable list, mutated by one thread per round. */
    record Order(java.util.List<String> items) { }

    public static class RecordCrossRound {
        static final Order ORDER = new Order(new java.util.ArrayList<>());

        @AsyncTest(threads = 1, invocations = 2, detectAll = false, detectRecordMutableComponentLeak = true)
        void body() {
            AsyncTestContext.recordMutableComponentLeakDetector().recordShared(ORDER, "order", Thread.currentThread());
            ORDER.items().add("item");
        }
    }

    /** The same record touched and mutated by two threads inside one round. */
    public static class RecordSameRound {
        static final Order ORDER = new Order(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));

        @AsyncTest(threads = 2, invocations = 1, detectAll = false, detectRecordMutableComponentLeak = true)
        void body() {
            AsyncTestContext.recordMutableComponentLeakDetector().recordShared(ORDER, "order", Thread.currentThread());
            ORDER.items().add("item");
        }
    }

    @Test
    @DisplayName("a record handed from round to round is not shared between threads")
    void recordCrossRoundIsNotShared() {
        run(RecordCrossRound.class);
        assertFalse(REPORTS.containsKey("RecordMutableComponentLeakDetector"),
                "one thread touched the record in each round, and the runner orders the rounds: "
                        + REPORTS.get("RecordMutableComponentLeakDetector"));
    }

    @Test
    @DisplayName("a record touched by two threads in one round is shared, so the silence above is not vacuous")
    void recordSameRoundIsShared() {
        run(RecordSameRound.class);
        assertTrue(REPORTS.containsKey("RecordMutableComponentLeakDetector"), "Reports: " + REPORTS.keySet());
    }

    /** A reflective final-field write, once per round, one thread per round. */
    public static class FinalFieldCrossRound {
        @AsyncTest(threads = 1, invocations = 2, detectAll = false, detectFinalFieldMutation = true)
        void body() {
            AsyncTestContext.finalFieldMutationDetector().recordMutation("Config.MAX", Thread.currentThread());
        }
    }

    /** The same write from two threads inside one round. */
    public static class FinalFieldSameRound {
        @AsyncTest(threads = 2, invocations = 1, detectAll = false, detectFinalFieldMutation = true)
        void body() {
            AsyncTestContext.finalFieldMutationDetector().recordMutation("Config.MAX", Thread.currentThread());
        }
    }

    @Test
    @DisplayName("final-field writes in successive rounds are ordered, so they are not concurrent mutators")
    void finalFieldWritesCrossRoundAreNotConcurrent() {
        run(FinalFieldCrossRound.class);
        String report = REPORTS.get("FinalFieldMutationDetector");
        assertTrue(report != null && report.contains("reflectively mutated"),
                "the mutation itself is still the finding: " + REPORTS.keySet());
        assertFalse(report.contains("Concurrent mutators"),
                "the runner joins round one before round two writes, so the writes are ordered: " + report);
    }

    @Test
    @DisplayName("final-field writes by two threads in one round are concurrent mutators, so the absence above is not vacuous")
    void finalFieldWritesSameRoundAreConcurrent() {
        run(FinalFieldSameRound.class);
        String report = REPORTS.get("FinalFieldMutationDetector");
        assertTrue(report != null && report.contains("Concurrent mutators"), "Report: " + report);
    }

    /** A counter the body resets and bumps once per round, one thread per round. */
    public static class LambdaCrossRound {
        static final int[] COUNTER = {0};
        static final Runnable TASK = () -> { };

        @AsyncTest(threads = 1, invocations = 2, detectAll = false, detectLambdaLostUpdate = true)
        void body() {
            COUNTER[0] = 0;
            int before = COUNTER[0];
            COUNTER[0] = before + 1;
            AsyncTestContext.lambdaLostUpdateDetector()
                    .recordReadModifyWrite(TASK, "counter", before, before + 1, Thread.currentThread());
        }
    }

    /** Two threads that both read 0 and both wrote 1, inside one round: a lost update. */
    public static class LambdaSameRound {
        static final Runnable TASK = () -> { };

        @AsyncTest(threads = 2, invocations = 1, detectAll = false, detectLambdaLostUpdate = true)
        void body() {
            AsyncTestContext.lambdaLostUpdateDetector()
                    .recordReadModifyWrite(TASK, "counter", 0, 1, Thread.currentThread());
        }
    }

    @Test
    @DisplayName("the same pre-value read in two rounds is not a lost update")
    void lambdaCrossRoundIsNotALostUpdate() {
        run(LambdaCrossRound.class);
        assertFalse(REPORTS.containsKey("LambdaLostUpdateDetector"),
                "each round reset the counter and made one update from it; no write was "
                        + "overwritten unread: " + REPORTS.get("LambdaLostUpdateDetector"));
    }

    @Test
    @DisplayName("two threads reading the same pre-value in one round lost an update, so the silence above is not vacuous")
    void lambdaSameRoundIsALostUpdate() {
        run(LambdaSameRound.class);
        assertTrue(REPORTS.containsKey("LambdaLostUpdateDetector"), "Reports: " + REPORTS.keySet());
    }
}
