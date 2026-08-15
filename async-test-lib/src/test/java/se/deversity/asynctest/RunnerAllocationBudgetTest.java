package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The inner-loop half of the performance contract: how much heap one {@code @AsyncTest} run
 * with every detector enabled is allowed to allocate per body execution.
 *
 * <p><strong>Why allocation, and why a ceiling.</strong> Wall-clock on a shared CI runner
 * varies by more than any regression this library would want to catch, so it is measured in
 * {@code load-tests/} and compared nightly, never asserted. Allocation is what the runner and
 * the detectors control directly, it is the cost a consumer pays on every invocation of every
 * stress test, and it is stable enough across machines to gate: the measurement below is the
 * JVM-wide {@code getTotalThreadAllocatedBytes()} delta around one engine run, divided by the
 * number of body executions. A detector that starts allocating per access, or a runner change
 * that boxes on the hot path, moves this number by integer factors; JIT and OS differences move
 * it by fractions.
 *
 * <p><strong>How the ceiling was set.</strong> Red first: the ceiling was 1 byte, the failure
 * message printed the measured cost, and the ceiling was set to about three times that number
 * so a JDK or runner change cannot trip it while a per-access allocation regression still does.
 * When it fails, the message carries the fresh measurement; re-derive the ceiling the same way
 * and say so in the commit, do not just raise it to the number that made the run green.
 *
 * <p>Runs through {@link EngineTestKit} like the other meta-tests, hence {@link E2E}: every CI
 * leg runs the e2e tier, so this holds on JDK 21 and 25 and on all three operating systems.
 */
@E2E
class RunnerAllocationBudgetTest {

    /** 4 threads x 50 invocations, so one run is 200 body executions. */
    static final int THREADS = 4;
    static final int INVOCATIONS = 50;

    /**
     * Bytes per body execution with every detector enabled, including the runner's per-round
     * and per-run overhead amortised over the 200 executions. Measured 2026-08-15 on JDK 21,
     * Windows 11, after one warmup run, three times: 25,985 / 26,013 / 26,599 bytes (spread
     * 2.4%, from a 1-byte ceiling's failure message). 80,000 is 3.0x the highest reading.
     */
    static final long CEILING_BYTES_PER_EXECUTION = 80_000L;

    @Test
    void oneAllDetectorRunStaysUnderTheAllocationCeiling() {
        var mx = ManagementFactory.getThreadMXBean();
        assumeTrue(mx instanceof com.sun.management.ThreadMXBean,
                "needs the HotSpot ThreadMXBean for per-thread allocation counters");
        var bean = (com.sun.management.ThreadMXBean) mx;
        assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting is off on this JVM");

        // Warmup: class loading, detector registry construction and JIT are one-time costs
        // that are not the contract; the second run is the steady state a consumer pays.
        run();

        long before = bean.getTotalThreadAllocatedBytes();
        Events events = run();
        long after = bean.getTotalThreadAllocatedBytes();

        assertEquals(0, events.failed().count(), "the empty body must not fail; the measurement "
                + "is meaningless otherwise");
        long executions = (long) THREADS * INVOCATIONS;
        long perExecution = (after - before) / executions;

        assertTrue(perExecution <= CEILING_BYTES_PER_EXECUTION,
                "One all-detector @AsyncTest run allocated " + perExecution + " bytes per body "
                        + "execution (" + (after - before) + " bytes over " + executions
                        + " executions), above the ceiling of " + CEILING_BYTES_PER_EXECUTION
                        + ". Either a detector or the runner started allocating on the per-access "
                        + "path, or the ceiling needs re-deriving (measure, then set ~3x; see the "
                        + "class javadoc).");
    }

    private static Events run() {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(EmptyBodyAllDetectors.class))
                .execute()
                .testEvents();
    }

    /** The subject: every detector on, a body that does nothing, so the cost is the harness. */
    public static class EmptyBodyAllDetectors {
        @AsyncTest(threads = THREADS, invocations = INVOCATIONS, detectAll = true,
                failOn = FailOn.NONE)
        void empty() {
        }
    }
}
