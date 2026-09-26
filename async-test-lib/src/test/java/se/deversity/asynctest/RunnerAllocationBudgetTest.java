package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;
import se.deversity.asynctest.diagnostics.SharedCollectionDetector;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

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
 * <p><strong>Two bodies.</strong> The empty body prices the harness: the runner, the rounds and
 * every detector's setup and analysis, with nothing recorded. It cannot see a record path, so an
 * allocation added to one passes it (#752). The recording body prices the record paths that are
 * meant to cost nothing, or close to it, per access once warm: {@code SelfGuard}'s lockset probe,
 * round window and {@link HappensBefore} stamp, reached through {@link SharedCollectionDetector},
 * and the volatile-read acquire the agent's load hook runs on the accessing
 * thread. It drives the first 1,024 times per execution and the second 512 times, so a few bytes
 * more per access move its cost by tens of kilobytes. The paths that store every access by design,
 * {@link RaceConditionDetector}'s field records (a volatile field among them) and the agent's
 * field events ({@link TelemetryRegistry#recordAccess}) drained through the bridge into the run's
 * atomicity detector, run a fixed few times per execution, together with a {@link HappensBefore}
 * release and acquire, so that they are on the measured path without drowning out the rest. No
 * agent is attached: the hooks are called the way the woven call sites call them.
 *
 * <p><strong>How the ceilings were set.</strong> Red first: the ceiling was 1 byte, the failure
 * message printed the measured cost, and the ceiling was set with a stated margin over the
 * highest reading, so a JDK or runner change cannot trip it while a per-access allocation
 * regression still does. When one fails, the message carries the fresh measurement; re-derive
 * the ceiling the same way and say so in the commit, do not just raise it to the number that
 * made the run green.
 *
 * <p>Runs through {@link EngineTestKit} like the other meta-tests, hence {@link E2E}: every CI
 * leg runs the e2e tier, so this holds on every JDK and operating system CI builds on.
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

    /**
     * Loop iterations per execution of the recording body; each makes two {@code SelfGuard}
     * accesses and one volatile read's acquire.
     */
    static final int ITERATIONS = 512;

    /** Runs of each body before measuring, for class loading and compilation. */
    static final int WARMUPS = 2;

    /** Measured runs of each body; the least of them counts. */
    static final int MEASURED = 3;

    /**
     * Bytes per body execution the recording body allocates beyond the empty body, each the least
     * of {@value #MEASURED} runs after {@value #WARMUPS} warmup runs of both, in one JVM.
     *
     * <p>Measured 2026-09-26 on Windows 11 from a 1-byte ceiling's failure message, one JVM per
     * reading: JDK 26 91,227 / 88,235 / 90,635; JDK 21 91,021 / 88,203 / 90,388; JDK 24 90,904;
     * held to two processors, JDK 26 85,116 / 85,622 and JDK 21 84,297 / 84,311. The spread is
     * 8.2% across all eleven and at most 3.4% within one JDK and processor count. 110,000 is 1.21x
     * the highest reading.
     *
     * <p>The margin is narrower than the empty body's on purpose, because the regression it has to
     * catch is small per access: one {@code new Object[4]} kept per {@code SelfGuard.noteAccess}
     * call, 32 bytes on each of 1,024 accesses per execution, measured 123,804 on JDK 26 and
     * 122,337 on JDK 21, while the empty body's test stayed green. The ceiling sits 18,773 bytes
     * above the highest reading, so it catches an object of 24 bytes or more per SelfGuard access
     * or stamp (1,024 per execution), or of 40 bytes or more per volatile read acquired (512);
     * the smallest object, 16 bytes, per SelfGuard access passes. The empty body's ceiling sees
     * none of these.
     *
     * <p>The difference, rather than the recording body alone, is asserted because the empty
     * body's own cost moves with the JDK and the processor count (31,267 to 34,432 bytes on JDK 21,
     * 38,230 to 44,004 on JDK 26 in the same runs) and says nothing about the record paths. The
     * least of the runs is taken because a collection or a late compilation only ever adds to a
     * run, while a per-access allocation adds to every run.
     */
    static final long RECORDING_CEILING_BYTES_PER_EXECUTION = 110_000L;

    @Test
    void oneAllDetectorRunStaysUnderTheAllocationCeiling() {
        var bean = allocationBean();

        // Warmup: class loading, detector registry construction and JIT are one-time costs
        // that are not the contract; the second run is the steady state a consumer pays.
        run(EmptyBodyAllDetectors.class);

        long before = bean.getTotalThreadAllocatedBytes();
        Events events = run(EmptyBodyAllDetectors.class);
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

    @Test
    void recordingThroughTheCommonPathsStaysUnderItsAllocationCeiling() {
        var bean = allocationBean();
        // What AsyncTestAgent.premain does, so the runner bridges the agent's field events into
        // each run's atomicity detector and the drain thread's share is part of the measurement.
        TelemetryRegistry.start();
        List<Long> empty = new ArrayList<>();
        List<Long> recording = new ArrayList<>();
        try {
            for (int i = 0; i < WARMUPS; i++) {
                run(EmptyBodyAllDetectors.class);
                run(RecordingBodyAllDetectors.class);
            }
            for (int i = 0; i < MEASURED; i++) {
                empty.add(perExecution(bean, EmptyBodyAllDetectors.class));
                recording.add(perExecution(bean, RecordingBodyAllDetectors.class));
            }
        } finally {
            TelemetryRegistry.stop();
        }
        long recordCost = recording.stream().mapToLong(Long::longValue).min().orElseThrow()
                - empty.stream().mapToLong(Long::longValue).min().orElseThrow();

        assertTrue(recordCost <= RECORDING_CEILING_BYTES_PER_EXECUTION,
                "Recording through the common paths allocated " + recordCost + " bytes per body "
                        + "execution beyond the empty body (recording " + recording + ", empty "
                        + empty + ", the least of each counts), above the ceiling of "
                        + RECORDING_CEILING_BYTES_PER_EXECUTION + ". Something on the SelfGuard, "
                        + "HappensBefore, RaceConditionDetector or agent field-event path started "
                        + "allocating per access, or the ceiling needs re-deriving (measure with a "
                        + "1-byte ceiling on each JDK CI runs, then set ~1.2x the highest; see "
                        + "RECORDING_CEILING_BYTES_PER_EXECUTION).");
    }

    /** {@return the bytes allocated JVM-wide per body execution over one run of {@code subject}} */
    private static long perExecution(com.sun.management.ThreadMXBean bean, Class<?> subject) {
        long before = bean.getTotalThreadAllocatedBytes();
        Events events = run(subject);
        long after = bean.getTotalThreadAllocatedBytes();
        assertEquals(0, events.failed().count(), subject.getSimpleName() + " must not fail; the "
                + "measurement is meaningless otherwise");
        return (after - before) / ((long) THREADS * INVOCATIONS);
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var mx = ManagementFactory.getThreadMXBean();
        assumeTrue(mx instanceof com.sun.management.ThreadMXBean,
                "needs the HotSpot ThreadMXBean for per-thread allocation counters");
        var bean = (com.sun.management.ThreadMXBean) mx;
        assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting is off on this JVM");
        return bean;
    }

    private static Events run(Class<?> subject) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(subject))
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

    /**
     * The subject: every detector on, a body that records through the common paths, so its cost
     * beyond the empty body's is what recording costs.
     */
    public static class RecordingBodyAllDetectors {

        /** The object the field records and the agent's events are about. */
        static final class Box {
            int value;
            volatile boolean ready;
        }

        // Fresh for every engine run, so no clock or tracked instance carries over from a warmup.
        final List<Integer> shared = new ArrayList<>();
        final Box box = new Box();
        final Object handOff = new Object();

        @AsyncTest(threads = THREADS, invocations = INVOCATIONS, detectAll = true,
                failOn = FailOn.NONE)
        void records() {
            SharedCollectionDetector collections = AsyncTestContext.sharedCollectionDetector();
            RaceConditionDetector races = AsyncTestContext.raceConditionDetector();
            long threadId = Thread.currentThread().threadId();
            HappensBefore.acquire(handOff);
            // The box's field accesses hold its own monitor, which both detectors see, so the run
            // reports nothing and the cost measured is recording's, not a report's.
            synchronized (box) {
                // What the weaver emits around a volatile read of Box.ready, before it and after it
                // with the value read, then before a plain read it marks as following that read.
                TelemetryRegistry.recordAccess(box, null, threadId, "Box.ready", false, true,
                        Integer.MIN_VALUE, false, false);
                TelemetryRegistry.volatileLoad(box, 1, "Box.ready");
                TelemetryRegistry.recordAccess(box, null, threadId, "Box.value", false, false,
                        Integer.MIN_VALUE, true, false);
                races.recordFieldRead(box, "ready");
                races.recordFieldRead(box, "value");
            }
            for (int i = 0; i < ITERATIONS; i++) {
                // Guarded, so the window never latches as shared and every access takes the whole
                // round path rather than the early return a finding allows.
                synchronized (shared) {
                    collections.recordRead(shared, "shared", "get");
                    collections.recordWrite(shared, "shared", "add");
                }
                // The hook the weaver emits after each volatile read: the field-clock lookup and
                // the acquire of the release whose value the read returned.
                TelemetryRegistry.volatileLoad(box, 1, "Box.ready");
            }
            // One publish per execution: a plain write, then the volatile write that releases it,
            // recorded by hand and by the agent's hook, then a release for the next acquirer.
            synchronized (box) {
                races.recordFieldWrite(box, "value");
                races.recordFieldWrite(box, "ready");
                TelemetryRegistry.recordAccess(box, null, threadId, "Box.ready", true, true,
                        Integer.MIN_VALUE, false, false);
                TelemetryRegistry.volatileStore(box, 1, "Box.ready");
            }
            HappensBefore.release(handOff);
        }
    }
}
