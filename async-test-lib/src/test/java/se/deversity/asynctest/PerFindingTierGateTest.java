package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import se.deversity.asynctest.diagnostics.ConfinedArenaThreadEscapeDetector;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the thing per-finding grades exist for: a verdict-grade finding failing a verdict-only gate
 * even though the detector that produced it is not a verdict-grade detector.
 *
 * <p><strong>Why this exists.</strong> A trust tier is a property of the detector and carries the
 * weakest grade it can produce, so a gate on {@code minTrust = VERDICT} cannot admit a finding the
 * library will not stand behind. `RecordMutableComponentLeakDetector` produces both kinds: an
 * observed mutation of a shared record's component is a fact, and a note that a shared record
 * merely holds a mutable component is a prompt. Rated as one detector it carries `PROMPT`, so
 * before per-finding grades a verdict-only gate stayed green on shared mutable state that had been
 * seen being mutated. That is the false negative this pair catches.
 */
@E2E
class PerFindingTierGateTest {

    @Test
    @DisplayName("the detector is still rated PROMPT, which is what makes this test meaningful")
    void theDetectorItselfIsNotAVerdictGradeDetector() {
        assertEquals(TrustTier.PROMPT, DetectorTrust.tierOf(DetectorType.RECORD_MUTABLE_COMPONENT_LEAK),
                "if this detector is ever promoted, the fixture below stops proving that a "
                        + "per-finding grade is what fails the gate, and needs a different subject");
    }

    @Test
    @DisplayName("an observed mutation fails a VERDICT-only gate, though its detector is PROMPT")
    void observedMutationTripsAVerdictOnlyGate() {
        Events tests = run(ObservedMutationDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("RecordMutableComponentLeakDetector")),
                "the failure must be the failOn gate naming the detector, not a fixture assertion. "
                        + "Failures seen: " + messages);
    }

    /**
     * A finding decided from the test's own record call must not reach a VERDICT-only gate, even
     * from a detector whose other path can.
     *
     * <p>{@code ConfinedArenaThreadEscapeDetector} reports an access after its arena closed on two
     * paths: the JVM answering {@code scope().isAlive() = false}, and a close the body recorded,
     * which is the test's own statement. Until #753 it graded both VERDICT by severity, so its
     * evidence class had to be the recorded path's ASSERTED and the clamp held the JVM-answered
     * verdicts at FACT too. Each grade now names its path's evidence: the detector is OBSERVED, and
     * the recorded close is a FACT on its own evidence.
     */
    @Test
    @DisplayName("an access after a recorded close does not trip a VERDICT-only gate")
    void aGradeAboveTheEvidenceCapDoesNotTripAVerdictOnlyGate() {
        assertEquals(TrustTier.VERDICT, DetectorTrust.evidenceOf(DetectorType.CONFINED_ARENA_THREAD_ESCAPE).cap(),
                "the detector's own cap admits VERDICT, so what holds this finding back is the "
                        + "evidence its grade names");
        run(RecordedCloseUnderVerdictFloorDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    /**
     * The other half of #753: the JVM-answered path of the same detector does fail a VERDICT-only
     * gate. Before grades named their evidence this finding was clamped to FACT with the recorded
     * one, and the gate below stayed green on a confinement violation the JVM itself reported.
     */
    @Test
    @DisplayName("a confinement violation the JVM answered trips a VERDICT-only gate")
    void aJvmAnsweredConfinementViolationTripsAVerdictOnlyGate() {
        assumeTrue(confinedArenaUsable(), "FFM Arena.ofConfined() is not usable on this JDK");
        Events tests = run(JvmRefusedAccessUnderVerdictFloorDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("ConfinedArenaThreadEscapeDetector")),
                "the failure must be the failOn gate naming the detector: " + messages);
    }

    @Test
    @DisplayName("the clamped finding still fails a gate at the tier its evidence carries")
    void theClampedFindingStillTripsAFactFloor() {
        Events tests = run(RecordedCloseUnderFactFloorDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("ConfinedArenaThreadEscapeDetector")),
                "the finding must still be reported and gated, only at FACT; without this the "
                        + "clamp test above would pass on a detector that never fired: " + messages);
    }

    @Test
    @DisplayName("a structural-risk-only finding still does not fail a VERDICT-only gate")
    void structuralRiskAloneDoesNotTripAVerdictOnlyGate() {
        run(StructuralRiskOnlyDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    /**
     * A leaked {@code ReentrantLock} fails a VERDICT-only gate (#837). The recorded acquire with no
     * release is arithmetic over the body's own record calls, a FACT on its own; the lock still
     * held for a worker thread that has ended is the JVM's answer, and makes the leak a verdict.
     * The runner shuts its executor down before it analyses, so the holder is never idle in a pool
     * there: it has ended, and the dummy waits for that rather than assuming it (#843).
     */
    @Test
    @DisplayName("a lock leak the lock itself confirms trips a VERDICT-only gate")
    void aLeakTheLockConfirmsTripsAVerdictOnlyGate() {
        Events tests = run(LeakedLockUnderVerdictFloorDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));

        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("LockLeakDetector")),
                "the failure must be the failOn gate naming the detector: " + messages);
    }

    @Test
    @DisplayName("a lock released in finally passes the same gate")
    void aLockReleasedInFinallyPassesAVerdictOnlyGate() {
        run(ReleasedLockUnderVerdictFloorDummy.class).assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    /**
     * A listener sees one {@code Violation} per report, and its {@code trustTier} is the
     * detector's, the weakest grade it can produce. Without the grades a listener, and so the
     * JSON output and corpus-eval's idiom lane, could not tell an observed mutation from a
     * structural note: both arrived as PROMPT (#837). The report's clamped grades now ride along.
     */
    @Test
    @DisplayName("a listener sees the tier of each graded finding, not only the detector's")
    void aListenerSeesTheGradeOfEachFinding() {
        List<se.deversity.asynctest.report.Violation> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onViolation(se.deversity.asynctest.report.Violation violation) {
                if ("RecordMutableComponentLeakDetector".equals(violation.detector())) {
                    seen.add(violation);
                }
            }
        };
        try (var registration = AsyncTestListenerRegistry.registerScoped(listener)) {
            run(StructuralRiskOnlyDummy.class);
            run(ObservedMutationDummy.class);
        }

        assertEquals(2, seen.size(), "one violation per fixture's report: " + seen);
        assertEquals("PROMPT", seen.get(0).attributes().get("trustTier"), "the detector's tier is unchanged");
        assertEquals("PROMPT", seen.get(0).attributes().get("findingTiers"),
                "the structural note alone is a prompt: " + seen.get(0).attributes().keySet());
        assertEquals("PROMPT", seen.get(1).attributes().get("trustTier"), "the detector's tier is unchanged");
        assertTrue(String.valueOf(seen.get(1).attributes().get("findingTiers")).contains("VERDICT"),
                "the observed mutation is graded VERDICT, and the listener has to see it: "
                        + seen.get(1).attributes().get("findingTiers"));
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /**
     * A record whose list component is genuinely mutated while two threads share it.
     *
     * <p>The list is synchronized on purpose. With a bare {@code ArrayList} the fixture raced
     * itself: two threads calling {@code add} can leave the size counter and the backing array
     * disagreeing, and the body then died with {@code Index 1 out of bounds for length 0} before
     * the {@code failOn} gate could speak — so the assertion below saw a fixture exception
     * instead of the gate message, and this test failed precisely when its subject misbehaved
     * most. The detector wants to see a write through a shared record's mutable component; it
     * does not need that write to be unsafe. See issue #353.
     */
    public static class ObservedMutationDummy {
        private final List<String> items = Collections.synchronizedList(new ArrayList<>());
        private final Order order = new Order("A-1", items);
        private final AtomicInteger seq = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectRecordMutableComponentLeak = true)
        void mutateTheSharedRecord() {
            AsyncTestContext.recordMutableComponentLeakDetector()
                    .recordShared(order, "order", Thread.currentThread());
            order.items().add("item-" + seq.getAndIncrement());
        }
    }

    /** The same record shape, shared but never written through. */
    public static class StructuralRiskOnlyDummy {
        private final Order order = new Order("A-2", new ArrayList<>(List.of("fixed")));

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectRecordMutableComponentLeak = true)
        void shareWithoutMutating() {
            AsyncTestContext.recordMutableComponentLeakDetector()
                    .recordShared(order, "order", Thread.currentThread());
            order.id();
        }
    }

    /**
     * The first worker takes the lock and never gives it back; the others find it taken.
     *
     * <p>The verdict rests on the holder having stopped by the time the runner analyses. Each
     * virtual worker runs one body and ends, and the ones that find the lock taken wait for the
     * holder's thread to end, bounded, so the second round cannot finish before it has: the holder
     * is terminated at analysis by construction rather than by how fast it was scheduled (#843).
     */
    public static class LeakedLockUnderVerdictFloorDummy {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicReference<Thread> holder = new AtomicReference<>();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectLockLeaks = true, useVirtualThreads = true)
        void leaveTheLockTaken() throws InterruptedException {
            if (lock.tryLock()) {
                AsyncTestContext.lockLeakDetector().recordLockAcquired(lock, "leaked");
                holder.set(Thread.currentThread());
                return;
            }
            Thread taken = holder.get();
            if (taken != null && !taken.join(Duration.ofSeconds(10))) {
                throw new AssertionError("the thread holding the lock never ended: " + taken.getState());
            }
        }
    }

    /** The same body with the unlock in a finally, which is the correct twin. */
    public static class ReleasedLockUnderVerdictFloorDummy {
        private final ReentrantLock lock = new ReentrantLock();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectLockLeaks = true)
        void releaseTheLock() {
            lock.lock();
            try {
                AsyncTestContext.lockLeakDetector().recordLockAcquired(lock, "released");
            } finally {
                lock.unlock();
                AsyncTestContext.lockLeakDetector().recordLockReleased(lock, "released");
            }
        }
    }

    /** An access after a close the body recorded, under a gate that admits only VERDICT. */
    public static class RecordedCloseUnderVerdictFloorDummy {
        @AsyncTest(threads = 1, invocations = 1, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectConfinedArenaThreadEscape = true)
        void accessAfterARecordedClose() {
            recordAccessAfterClose();
        }
    }

    /** The same finding under a gate that admits FACT. */
    public static class RecordedCloseUnderFactFloorDummy {
        @AsyncTest(threads = 1, invocations = 1, failOn = FailOn.HIGH, minTrust = TrustTier.FACT,
                   detectAll = false, detectConfinedArenaThreadEscape = true)
        void accessAfterARecordedClose() {
            recordAccessAfterClose();
        }
    }

    /** A real confined segment touched from a thread that does not own it, under a VERDICT-only gate. */
    public static class JvmRefusedAccessUnderVerdictFloorDummy {
        @AsyncTest(threads = 1, invocations = 1, failOn = FailOn.HIGH, minTrust = TrustTier.VERDICT,
                   detectAll = false, detectConfinedArenaThreadEscape = true)
        void touchAConfinedSegmentFromAnotherThread() throws Exception {
            Class<?> arenaType = Class.forName("java.lang.foreign.Arena");
            Object arena = arenaType.getMethod("ofConfined").invoke(null);
            Object segment = arenaType.getMethod("allocate", long.class).invoke(arena, 16L);
            ConfinedArenaThreadEscapeDetector detector = AsyncTestContext.confinedArenaThreadEscapeDetector();
            detector.recordArena(arena, "native", Thread.currentThread());
            detector.recordAllocation(segment, arena, "native", 16);
            // A thread that owns nothing: the JVM answers isAccessibleBy = false for it.
            detector.recordAccess(segment, "native", new Thread(() -> { }, "other-carrier"), true);
            ((AutoCloseable) arena).close();
        }
    }

    /** {@return whether this JDK has the final FFM API the JVM-answered path asks} */
    private static boolean confinedArenaUsable() {
        try {
            Class.forName("java.lang.foreign.MemorySegment").getMethod("isAccessibleBy", Thread.class);
            Class.forName("java.lang.foreign.Arena").getMethod("ofConfined");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Plain objects stand in for the arena and the segment, so the JVM has nothing to answer and
     * the close the body records is the only evidence the finding has.
     */
    private static void recordAccessAfterClose() {
        Object arena = new Object();
        Object segment = new Object();
        ConfinedArenaThreadEscapeDetector detector = AsyncTestContext.confinedArenaThreadEscapeDetector();
        detector.recordArena(arena, "arena", Thread.currentThread());
        detector.recordAllocation(segment, arena, "segment", 16);
        detector.recordClose(arena, Thread.currentThread());
        detector.recordAccess(segment, "segment", Thread.currentThread(), false);
    }

    /** Shallowly immutable: the list reference is final, the list is not. */
    public record Order(String id, List<String> items) { }
}
