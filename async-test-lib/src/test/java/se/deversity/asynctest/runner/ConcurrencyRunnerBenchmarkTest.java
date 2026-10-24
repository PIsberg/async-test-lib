package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.benchmark.BenchmarkRegressionException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The benchmark step of the runner's cleanup, through the runner rather than the recorder alone.
 *
 * <p>{@code BenchmarkRecorderTest} drives the recorder directly, so nothing ran the runner's own
 * benchmark path, and the runner wrapped {@code complete()} in a {@code catch (Exception)} that logged
 * a WARN. {@code BenchmarkRegressionException} is a {@code RuntimeException}, so the one failure
 * {@code failOnBenchmarkRegression = true} exists to raise was logged as "Benchmark completion
 * failed" and the run passed.
 */
class ConcurrencyRunnerBenchmarkTest {

    @TempDir
    Path store;

    private String savedStore;

    @BeforeEach
    void pointTheStoreAtAFreshFile() {
        savedStore = System.getProperty("benchmark.store.path");
        System.setProperty("benchmark.store.path", store.resolve("baselines.dat").toString());
    }

    @AfterEach
    void restore() {
        if (savedStore == null) {
            System.clearProperty("benchmark.store.path");
        } else {
            System.setProperty("benchmark.store.path", savedStore);
        }
    }

    private static AsyncTestConfig benchmarked(boolean failOnRegression) {
        return AsyncTestConfig.builder().threads(2).invocations(3).licenseMockMode(true)
                .enableBenchmarking(true).benchmarkRegressionThreshold(0.2)
                .failOnBenchmarkRegression(failOnRegression).build();
    }

    @Test
    void aRegressionFailsTheRun_whenFailOnBenchmarkRegressionIsSet() throws Throwable {
        AsyncTestRunner.run("benchmarked", benchmarked(true), () -> { });   // the baseline

        BenchmarkRegressionException ex = assertThrows(BenchmarkRegressionException.class,
                () -> AsyncTestRunner.run("benchmarked", benchmarked(true), () -> Thread.sleep(20)),
                "a 20 ms body against an empty-body baseline is a regression far past 20%, and "
                        + "failOnBenchmarkRegression must fail the run for it");
        assertTrue(ex.getMessage().contains("benchmarked"), ex.getMessage());
    }

    @Test
    void aRegressionIsOnlyReported_whenFailOnBenchmarkRegressionIsNotSet() throws Throwable {
        AsyncTestRunner.run("reported", benchmarked(false), () -> { });

        assertDoesNotThrow(() -> AsyncTestRunner.run("reported", benchmarked(false), () -> Thread.sleep(20)));
    }

    @Test
    void aBodyFailureOutranksTheRegression() throws Throwable {
        AsyncTestRunner.run("failing", benchmarked(true), () -> { });

        AssertionError ex = assertThrows(AssertionError.class,
                () -> AsyncTestRunner.run("failing", benchmarked(true), () -> {
                    Thread.sleep(20);
                    throw new IllegalStateException("the body's own failure");
                }));
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "the body's failure is the run's result; the regression must not replace it: " + ex);
    }
}
