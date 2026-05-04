package com.github.asynctest.loadtest;

import com.github.asynctest.AsyncTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Measures peak heap usage while running @AsyncTest scenarios with and without detectors.
 * Sweeps over invocation counts at a fixed thread count.
 *
 * Output: results/<version>/memory.csv
 */
public class MemoryStressTest {

    record Config(Class<?> noDet, Class<?> allDet, int threads, int invocations) {}

    private static final Queue<String> rows = new ConcurrentLinkedQueue<>();

    static Stream<Config> configs() {
        boolean fast = Boolean.getBoolean("load.test.fast");
        List<Config> all = List.of(
            new Config(T4_I10_None.class,  T4_I10_All.class,  4,  10),
            new Config(T4_I50_None.class,  T4_I50_All.class,  4,  50),
            new Config(T4_I100_None.class, T4_I100_All.class, 4, 100),
            new Config(T4_I500_None.class, T4_I500_All.class, 4, 500)
        );
        if (fast) {
            return all.stream().filter(c -> c.invocations() <= 50);
        }
        return all.stream();
    }

    @ParameterizedTest(name = "t={1} i={2}")
    @MethodSource("configs")
    void measure(Config config) throws Exception {
        System.out.printf("  Memory: threads=%d invocations=%d%n",
                config.threads(), config.invocations());

        // Warmup both paths then GC before measuring
        runQuiet(config.noDet());
        runQuiet(config.allDet());
        gc();

        long peakNone = measurePeakHeap(config.noDet());
        gc();
        long peakAll = measurePeakHeap(config.allDet());

        double noneKb = peakNone / 1024.0;
        double allKb  = peakAll  / 1024.0;
        double overheadKb = Math.max(0, allKb - noneKb);
        double overheadPct = noneKb > 0 ? (overheadKb / noneKb) * 100.0 : 0.0;

        rows.add(String.format("%d,%d,%.1f,%.1f,%.1f,%.1f",
                config.threads(), config.invocations(),
                noneKb, allKb, overheadKb, overheadPct));
    }

    private static long measurePeakHeap(Class<?> target) throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        AtomicBoolean sampling = new AtomicBoolean(true);
        long[] peak = {rt.totalMemory() - rt.freeMemory()};

        Thread sampler = Thread.startVirtualThread(() -> {
            while (sampling.get()) {
                long used = rt.totalMemory() - rt.freeMemory();
                if (used > peak[0]) peak[0] = used;
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        });

        long before = rt.totalMemory() - rt.freeMemory();
        runQuiet(target);
        sampling.set(false);
        sampler.join();

        return Math.max(0, peak[0] - before);
    }

    private static void runQuiet(Class<?> target) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(target))
                .execute();
    }

    private static void gc() throws InterruptedException {
        System.gc();
        Thread.sleep(150);
    }

    @AfterAll
    static void writeResults() throws IOException {
        String version = System.getProperty("async.test.version", "unknown");
        String outputDir = System.getProperty("load.test.output.dir",
                System.getProperty("java.io.tmpdir"));

        Path out = Paths.get(outputDir, "memory.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            w.printf("# async-test-lib load-test memory sweep%n");
            w.printf("# version=%s date=%s%n", version, Instant.now());
            w.println("threads,invocations,noDetectorAllocKB,allDetectorAllocKB,overheadKB,overheadPct");
            rows.forEach(w::println);
        }
        System.out.println("Memory results written to: " + out);
    }

    // ── Target classes (excluded from direct JUnit discovery via *$* build filter) ─────────────

    public static class T4_I10_None {
        @AsyncTest(threads = 4, invocations = 10,  detectAll = false) public void run() {}
    }
    public static class T4_I50_None {
        @AsyncTest(threads = 4, invocations = 50,  detectAll = false) public void run() {}
    }
    public static class T4_I100_None {
        @AsyncTest(threads = 4, invocations = 100, detectAll = false) public void run() {}
    }
    public static class T4_I500_None {
        @AsyncTest(threads = 4, invocations = 500, detectAll = false) public void run() {}
    }
    public static class T4_I10_All {
        @AsyncTest(threads = 4, invocations = 10,  detectAll = true) public void run() {}
    }
    public static class T4_I50_All {
        @AsyncTest(threads = 4, invocations = 50,  detectAll = true) public void run() {}
    }
    public static class T4_I100_All {
        @AsyncTest(threads = 4, invocations = 100, detectAll = true) public void run() {}
    }
    public static class T4_I500_All {
        @AsyncTest(threads = 4, invocations = 500, detectAll = true) public void run() {}
    }
}
