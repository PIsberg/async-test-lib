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
