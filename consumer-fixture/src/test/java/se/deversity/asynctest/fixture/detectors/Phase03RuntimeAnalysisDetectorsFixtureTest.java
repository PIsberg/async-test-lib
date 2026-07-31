package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.concurrent.atomic.AtomicInteger;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 3, runtime-analysis group — {@code RACE_CONDITIONS} through
 * {@code INTERRUPT_MISHANDLING}.
 *
 * <p>Four of these five had no public per-detector accessor until 1.7.0 — only
 * {@code ATOMICITY_VIOLATIONS} did, which is what made the gap look like an oversight rather
 * than a design: all five sit in the same registry group and all five expose {@code record*}
 * methods written for a test body to call. Each fixture now drives those methods the way a
 * consumer would.
 *
 * <p>Corresponding examples: {@code examples/08-race-condition},
 * {@code examples/23-thread-local-leak}, {@code examples/21-busy-wait},
 * {@code examples/22-atomicity-violation}, {@code examples/24-interrupt-mishandling}.
 */
class Phase03RuntimeAnalysisDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.RACE_CONDITIONS})
    void raceConditions() {
        reachable("raceConditionDetector()", AsyncTestContext::raceConditionDetector);

        // Unsynchronised read-modify-write on shared state: the classic race, recorded as
        // the read/write pair the detector pairs up across threads.
        AsyncTestContext.raceConditionDetector().recordFieldRead(UNGUARDED, "value");
        int next = UNGUARDED.value + 1;
        AsyncTestContext.raceConditionDetector().recordFieldWrite(UNGUARDED, "value");
        UNGUARDED.value = next;
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LOCAL_LEAKS})
    void threadLocalLeaks() {
        reachable("threadLocalMonitor()", AsyncTestContext::threadLocalMonitor);

        // Set on a pooled/virtual carrier and removed again — the leak is forgetting
        // remove(), which is exactly what the init/access/cleanup triple makes visible.
        var monitor = AsyncTestContext.threadLocalMonitor();
        monitor.recordThreadLocalInit(LEAKY, "fixture-request-context");
        LEAKY.set("per-worker state");
        try {
            monitor.recordThreadLocalAccess(LEAKY);
            spin(32);
        } finally {
            LEAKY.remove();
            monitor.recordThreadLocalCleanup(LEAKY);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.BUSY_WAITING})
    void busyWaiting() {
        reachable("busyWaitDetector()", AsyncTestContext::busyWaitDetector);

        // A bounded spin loop — the shape of a busy-wait, without the unbounded part.
        var detector = AsyncTestContext.busyWaitDetector();
        AtomicInteger gate = new AtomicInteger();
        int iterations = 0;
        for (int i = 0; i < 128 && gate.get() == 0; i++) {
            detector.recordLoopIteration();
            iterations++;
            Thread.onSpinWait();
            if (i == 127) {
                gate.set(1);
            }
        }
        detector.recordYield();
        detector.reportSpinLoop("fixture bounded spin", iterations);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.ATOMICITY_VIOLATIONS})
    void atomicityViolations() {
        reachable("atomicityValidator()", AsyncTestContext::atomicityValidator);

        // Two atomic operations that are not atomic together: check-then-act on an atomic.
        var validator = AsyncTestContext.atomicityValidator();
        AtomicInteger counter = new AtomicInteger();
        validator.recordCompoundOperationStart("checkThenIncrement");
        int seen = counter.get();
        validator.recordFieldAccess("counter", seen, false);
        if (seen < 10) {
            validator.recordFieldAccess("counter", counter.incrementAndGet(), true);
        }
        validator.recordCompoundOperationEnd("checkThenIncrement");
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.INTERRUPT_MISHANDLING})
    void interruptMishandling() {
        reachable("interruptMonitor()", AsyncTestContext::interruptMonitor);

        // Catch and restore, which is the correct handling the detector contrasts against
        // the swallowing form covered by INTERRUPT_SWALLOWING. Recording both halves is
        // what tells the two apart.
        var monitor = AsyncTestContext.interruptMonitor();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            monitor.recordInterruptException(e);
            Thread.currentThread().interrupt();
            monitor.recordInterruptRestored();
        }
    }

    /** Shared, unguarded, mutated by every worker — deliberately. */
    private static final Counter UNGUARDED = new Counter();

    private static final ThreadLocal<String> LEAKY = new ThreadLocal<>();

    private static final class Counter {
        private int value;
    }
}
