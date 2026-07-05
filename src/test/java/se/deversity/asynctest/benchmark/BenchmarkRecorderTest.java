package se.deversity.asynctest.benchmark;

import se.deversity.asynctest.AsyncTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRecorderTest {

    @TempDir
    Path tempDir;

    private AsyncTestConfig disabledConfig() {
        return AsyncTestConfig.builder()
                .enableBenchmarking(false)
                .build();
    }

    private AsyncTestConfig enabledConfig() {
        // Point the store at a temp location via system property override
        // (set before constructing the recorder)
        return AsyncTestConfig.builder()
                .enableBenchmarking(true)
                .benchmarkRegressionThreshold(0.2)
                .failOnBenchmarkRegression(false)
                .threads(2)
                .invocations(5)
                .build();
    }

    // ---- disabled benchmarking ----

    @Test
    void isBenchmarkingEnabled_disabled_returnsFalse() {
        BenchmarkRecorder recorder = new BenchmarkRecorder(disabledConfig(), "Cls", "m");
        assertFalse(recorder.isBenchmarkingEnabled());
    }

    @Test
    void recordInvocationStart_disabled_returnsZero() {
        BenchmarkRecorder recorder = new BenchmarkRecorder(disabledConfig(), "Cls", "m");
        assertEquals(0, recorder.recordInvocationStart());
    }

    @Test
    void recordInvocationEnd_disabled_doesNotIncreaseCount() {
        BenchmarkRecorder recorder = new BenchmarkRecorder(disabledConfig(), "Cls", "m");
        recorder.recordInvocationEnd(0);
        assertEquals(0, recorder.getInvocationCount());
    }

    @Test
    void complete_disabled_returnsNull() {
        BenchmarkRecorder recorder = new BenchmarkRecorder(disabledConfig(), "Cls", "m");
        assertNull(recorder.complete());
    }

    // ---- enabled benchmarking ----

    @Test
    void isBenchmarkingEnabled_enabled_returnsTrue() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            assertTrue(recorder.isBenchmarkingEnabled());
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void recordInvocationStart_enabled_returnsNonZero() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            long start = recorder.recordInvocationStart();
            assertTrue(start > 0, "recordInvocationStart should return current nanoTime when enabled");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void recordInvocationEnd_enabled_incrementsCount() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            long start = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start);
            assertEquals(1, recorder.getInvocationCount());
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void getInvocationCount_multipleRecordings_returnsCorrectCount() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            for (int i = 0; i < 5; i++) {
                long start = recorder.recordInvocationStart();
                recorder.recordInvocationEnd(start);
            }
            assertEquals(5, recorder.getInvocationCount());
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void getTotalExecutionTimeNanos_returnsPositiveElapsed() throws InterruptedException {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            Thread.sleep(5);
            long elapsed = recorder.getTotalExecutionTimeNanos();
            assertTrue(elapsed > 0, "elapsed time should be positive");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    // ---- complete() with invocations ----

    @Test
    void complete_withInvocations_returnsFirstRunOnNewStore() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            long start = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start);

            BenchmarkComparisonResult result = recorder.complete();

            assertNotNull(result, "complete() should return a result when invocations exist");
            assertTrue(result.isFirstRun(), "no baseline yet — should be first run");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void complete_noInvocations_returnsNull() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");
            // No calls to recordInvocationEnd
            assertNull(recorder.complete(), "complete() with no invocations should return null");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    // ---- actualThreads override (4-arg constructor) ----

    @Test
    void threeArgConstructor_recordsConfigThreads() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m"); // threads=2
            long start = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start);

            BenchmarkComparisonResult result = recorder.complete();

            assertEquals(2, result.getCurrentResult().getThreads(),
                    "the 3-arg constructor must record config.threads as before");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    @Test
    void fourArgConstructor_recordsActualThreadsOverride_notConfigThreads() {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            AsyncTestConfig cfg = AsyncTestConfig.builder()
                    .enableBenchmarking(true)
                    .benchmarkRegressionThreshold(0.2)
                    .failOnBenchmarkRegression(false)
                    .threads(2)
                    .invocations(5)
                    .build();
            // Simulates virtualThreadStressMode overriding config.threads at runtime.
            BenchmarkRecorder recorder = new BenchmarkRecorder(cfg, "Cls", "m", 7);
            long start = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start);

            BenchmarkComparisonResult result = recorder.complete();

            assertEquals(7, result.getCurrentResult().getThreads(),
                    "actualThreads passed to the 4-arg constructor must be recorded on the baseline, not config.threads");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }

    // ---- avgTime computed from recorded per-round samples, not wall-clock total ----

    @Test
    void complete_avgIsComputedFromRecordedSamples_notWallClockTotal() throws InterruptedException {
        System.setProperty("benchmark.store.path", tempDir.resolve("store.dat").toString());
        try {
            BenchmarkRecorder recorder = new BenchmarkRecorder(enabledConfig(), "Cls", "m");

            long start1 = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start1); // near-instant round

            // Significant dead time that is NOT part of any recorded round (analogous to
            // lifecycle methods / barrier waits between invocation rounds in the real
            // runner). This must not inflate avgTime.
            Thread.sleep(50);

            long start2 = recorder.recordInvocationStart();
            recorder.recordInvocationEnd(start2); // near-instant round

            BenchmarkComparisonResult result = recorder.complete();
            long avgNanos = result.getCurrentResult().getAvgTimePerInvocationNanos();

            // If avg were still wall-clock-total / count, it would include the 50ms dead
            // time and land in the tens-of-milliseconds range. The true per-round average
            // (sum of two near-instant samples / 2) must be far smaller.
            assertTrue(avgNanos < 5_000_000L,
                    "avgTime must derive from the sum of recorded per-round samples, not "
                            + "wall-clock time spanning the gap between rounds; was " + avgNanos + "ns");
        } finally {
            System.clearProperty("benchmark.store.path");
        }
    }
}
