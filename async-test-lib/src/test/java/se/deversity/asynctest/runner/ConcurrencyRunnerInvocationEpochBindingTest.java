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
}
