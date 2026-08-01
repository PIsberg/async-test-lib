package se.deversity.asynctest.loadtest;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Measures the library's own per-test-method costs, isolated from JUnit.
 *
 * <p>{@link AsyncTestBenchmark} runs a whole {@code @AsyncTest} through
 * {@code EngineTestKit}, which is the right shape for an end-to-end number but the wrong
 * instrument for detector cost: platform discovery and engine setup dominate the measurement, so
 * turning all 127 detectors on moves the total by a couple of percent — inside the run-to-run
 * spread. A detector that got ten times slower would not show up. {@link #engineHarnessOnly()}
 * measures that floor directly, so the claim is checkable rather than asserted.
 *
 * <p>The two costs that actually scale with the detector set both happen once per test method:
 * building the registry ({@link #contextConstruction_allDetectors()}) and sweeping it for findings
 * ({@link #analyzeSweep_allDetectors()}). Those are what a suite with thousands of
 * {@code @AsyncTest} methods pays over and over, and until now nothing measured them.
 *
 * <p>Run: {@code ./gradlew -p load-tests jmh -PasyncTestVersion=<version>}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class DetectorLifecycleBenchmark {

    private AsyncTestConfig allDetectors;
    private AsyncTestConfig noDetectors;
    private AsyncTestContext warmContext;

    @Setup
    public void setUp() {
        allDetectors = AsyncTestConfig.builder().threads(4).invocations(1).detectAll(true).build();
        noDetectors = AsyncTestConfig.builder().threads(4).invocations(1).detectAll(false).build();
        warmContext = new AsyncTestContext(allDetectors);
    }

    // ── Per-test-method costs, isolated ──────────────────────────────────────────────────────

    /** Building the full 127-detector registry: paid once per {@code @AsyncTest} method. */
    @Benchmark
    public AsyncTestContext contextConstruction_allDetectors() {
        return new AsyncTestContext(allDetectors);
    }

    /** The same construction with every detector off — the floor the number above sits on. */
    @Benchmark
    public AsyncTestContext contextConstruction_noDetectors() {
        return new AsyncTestContext(noDetectors);
    }

    /**
     * The end-of-run sweep over every detector. Uses a context that saw no recorded activity, so
     * this is the cost of asking 127 detectors "did you see anything?", not the cost of a finding.
     */
    @Benchmark
    public List<String> analyzeSweep_allDetectors() {
        return warmContext.analyzeAll();
    }

    // ── The floor that AsyncTestBenchmark's numbers are sitting on ───────────────────────────

    /**
     * A plain {@code @Test} through the same {@code EngineTestKit} path
     * {@link AsyncTestBenchmark} uses. Whatever this costs is present in every number that
     * benchmark reports, and none of it is library work.
     */
    @Benchmark
    public void engineHarnessOnly(Blackhole bh) {
        bh.consume(EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(PlainTest.class))
                .execute()
                .testEvents());
    }

    public static class PlainTest {
        @org.junit.jupiter.api.Test
        public void run() {
            // Deliberately empty: the measurement is the harness around it.
        }
    }
}
