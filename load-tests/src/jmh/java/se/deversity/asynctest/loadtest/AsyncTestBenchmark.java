package se.deversity.asynctest.loadtest;

import se.deversity.asynctest.AsyncTest;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * JMH microbenchmarks measuring the async-test-lib framework overhead with and without detectors.
 * Each benchmark runs a no-op @AsyncTest scenario end-to-end via EngineTestKit.
 *
 * Run: ./gradlew -p load-tests jmh -PasyncTestVersion=0.8.0
 * Output: load-tests/build/jmh-results.json  (copy to results/<version>/jmh.json)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class AsyncTestBenchmark {

    // ── No-detector baselines ────────────────────────────────────────────────────────────────

    @Benchmark
    public Object frameworkOverhead_t2_noDetectors() {
        return run(T2_I10_None.class);
    }

    @Benchmark
    public Object frameworkOverhead_t4_noDetectors() {
        return run(T4_I10_None.class);
    }

    @Benchmark
    public Object frameworkOverhead_t8_noDetectors() {
        return run(T8_I10_None.class);
    }

    // ── All-detectors (full overhead) ────────────────────────────────────────────────────────

    @Benchmark
    public Object detectorOverhead_t2_allDetectors() {
        return run(T2_I10_All.class);
    }

    @Benchmark
    public Object detectorOverhead_t4_allDetectors() {
        return run(T4_I10_All.class);
    }

    @Benchmark
    public Object detectorOverhead_t8_allDetectors() {
        return run(T8_I10_All.class);
    }

    private static Object run(Class<?> target) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(target))
                .execute()
                .testEvents();
    }

    // ── Target classes ───────────────────────────────────────────────────────────────────────

    public static class T2_I10_None {
        @AsyncTest(threads = 2, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T4_I10_None {
        @AsyncTest(threads = 4, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T8_I10_None {
        @AsyncTest(threads = 8, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T2_I10_All {
        @AsyncTest(threads = 2, invocations = 10, detectAll = true) public void run() {}
    }
    public static class T4_I10_All {
        @AsyncTest(threads = 4, invocations = 10, detectAll = true) public void run() {}
    }
    public static class T8_I10_All {
        @AsyncTest(threads = 8, invocations = 10, detectAll = true) public void run() {}
    }
}
