package se.deversity.asynctest.benchmark;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dogfoods {@link BenchmarkRecorder}'s recording pair with {@code @AsyncTest}.
 *
 * <p>Why this exists: {@code recordInvocationEnd} appends to a plain {@code ArrayList} and is
 * declared to run on the hot path inside every invocation round, so the {@code synchronized} block
 * around the append is the only thing standing between concurrent rounds and a silently short
 * benchmark sample. An {@code ArrayList} losing an append does not throw; it just holds fewer
 * elements than it was given, and every number the benchmark then reports is computed over a
 * sample nobody knows is incomplete.
 *
 * <p>That is a counting property, so the test counts: {@link #THREADS} workers record for
 * {@link #ROUNDS} rounds against one shared recorder, and the recorder must hold exactly as many
 * timings as it was handed. One lost append moves the number.
 *
 * <p>The recorder is built directly rather than taken from a run, so benchmarking can be switched
 * on through the config without touching the enclosing run. Nothing here calls {@code complete()},
 * so no baseline store is read or written.
 */
class BenchmarkRecorderDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 200;

    private static final BenchmarkRecorder RECORDER = new BenchmarkRecorder(
            AsyncTestConfig.builder()
                    .threads(THREADS)
                    .invocations(ROUNDS)
                    .enableBenchmarking(true)
                    .build(),
            BenchmarkRecorderDogfoodTest.class.getName(),
            "everyWorkerRecordsItsOwnTiming");

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 20_000)
    void everyWorkerRecordsItsOwnTiming() {
        long start = RECORDER.recordInvocationStart();

        // A recorder with benchmarking off returns 0 and drops every append on the floor, which
        // would make the count below pass for the wrong reason.
        assertNotEquals(0L, start, "benchmarking is off, so this test asserts nothing");

        RECORDER.recordInvocationEnd(start);
    }

    @AfterAll
    static void theRecorderHeldEveryTimingItWasHanded() {
        assertEquals(THREADS * ROUNDS, RECORDER.getInvocationCount(),
                "the recorder holds fewer timings than it was handed: appends were lost under "
                        + "contention and every benchmark number is computed over a short sample");
    }
}
