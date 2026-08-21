package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@code minTrust} changes: which findings may fail a build.
 *
 * <p><strong>Why this exists.</strong> {@code failOn} asks how bad a finding would be if it were
 * real, and nothing asked whether it is real. Of the 142 detectors, three are backed by a measured
 * case that fires on the bug and stays silent on its correctly synchronized twin; most of the rest
 * report a pattern they cannot fully model and mean "go and look". Gating a merge on all of them
 * alike is how a team learns to ignore the report.
 *
 * <p>The three fixtures below are the same two bugs seen through different floors, so the tests
 * fail if the floor stops filtering, if it filters too much, or if it silently drops back to the
 * old behaviour where every finding could fail a build.
 */
@E2E
class MinTrustGateTest {

    @Test
    @DisplayName("by default a PROMPT-tier finding still fails the gate, as it always did")
    void promptTierFindingFailsWithTheDefaultFloor() {
        Events tests = run(RecordedRaceDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));
        assertGateFailure(tests, "RaceConditionDetector");
    }

    @Test
    @DisplayName("minTrust=VERDICT spares the same finding, because nobody measured that detector")
    void promptTierFindingIsSparedByTheVerdictFloor() {
        run(RecordedRaceUnderVerdictFloorDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    @Test
    @DisplayName("minTrust=VERDICT still fails on a measured finding, which is the point of the floor")
    void verdictTierFindingStillFailsUnderTheVerdictFloor() {
        Events tests = run(AtomicMisuseUnderVerdictFloorDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));
        assertGateFailure(tests, "AtomicNonAtomicUpdateDetector");
    }

    /**
     * Asserts the run failed because the {@code failOn} gate tripped on {@code detector}, not for
     * some other reason.
     *
     * <p>Worth the extra assertion because a deadlock fixture has a second way to fail: the round
     * can time out. Counting failures alone would go green whether the gate worked or not, which
     * is the most expensive kind of green.
     */
    private static void assertGateFailure(Events tests, String detector) {
        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=") && m.contains(detector)),
                "the failure must be the failOn gate naming " + detector + ", not a timeout or a "
                        + "fixture assertion. Failures seen: " + messages);
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /**
     * A race recorded through the public API, which is the documented path for feeding a detector.
     * RaceConditionDetector is classified PROMPT: it cannot see a lock nobody declared, so a
     * finding is a prompt to verify rather than proof of a bug.
     */
    public static class RecordedRaceDummy {
        private final Counter counter = new Counter();

        @AsyncTest(threads = 4, invocations = 5, failOn = FailOn.HIGH,
                   detectAll = false, detectRaceConditions = true)
        void racyIncrement() {
            AsyncTestContext.raceConditionDetector().recordFieldRead(counter, "value");
            int current = counter.value;
            Thread.yield();
            counter.value = current + 1;
            AsyncTestContext.raceConditionDetector().recordFieldWrite(counter, "value");
        }
    }

    /** The same bug, the same detector, with the gate told to fail only on measured findings. */
    public static class RecordedRaceUnderVerdictFloorDummy {
        private final Counter counter = new Counter();

        @AsyncTest(threads = 4, invocations = 5, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectRaceConditions = true)
        void racyIncrement() {
            AsyncTestContext.raceConditionDetector().recordFieldRead(counter, "value");
            int current = counter.value;
            Thread.yield();
            counter.value = current + 1;
            AsyncTestContext.raceConditionDetector().recordFieldWrite(counter, "value");
        }
    }

    /**
     * A get-then-set on an {@link AtomicInteger}, which is a lost update between the two calls: the
     * atomic type does not make the compound operation atomic.
     *
     * <p>AtomicNonAtomicUpdateDetector is one of the three detectors classified VERDICT, so the
     * highest floor must not spare it. It replaced a deadlock fixture here, which looked like the
     * obvious choice and proved nothing: a deadlocked round fails on the timeout before the
     * {@code failOn} gate ever runs, so the test went green whether the floor worked or not.
     */
    public static class AtomicMisuseUnderVerdictFloorDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(threads = 4, invocations = 5, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectAtomicNonAtomicUpdates = true)
        void getThenSet() {
            int current = counter.get();
            AsyncTestContext.atomicNonAtomicUpdateDetector()
                    .recordGet(counter, "counter", Thread.currentThread());
            Thread.yield();
            counter.set(current + 1);
            AsyncTestContext.atomicNonAtomicUpdateDetector()
                    .recordSet(counter, "counter", Thread.currentThread());
        }
    }

    /** A shared counter whose accesses reach the detector through the public recording API. */
    static final class Counter {
        int value;
    }
}
