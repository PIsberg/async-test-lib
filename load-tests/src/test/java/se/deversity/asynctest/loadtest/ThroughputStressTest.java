package se.deversity.asynctest.loadtest;

import se.deversity.asynctest.AsyncTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Sweeps @AsyncTest across thread counts and invocation counts, measuring wall-clock throughput.
 * Inner target classes are excluded from direct JUnit discovery (*$* filter in build.gradle.kts)
 * and are run only via EngineTestKit.
 *
 * Output: results/<version>/throughput.csv
 */
public class ThroughputStressTest {

    record Config(Class<?> target, int threads, int invocations, boolean detectAll) {}

    private static final Queue<String> rows = new ConcurrentLinkedQueue<>();

    static Stream<Config> configs() {
        boolean fast = Boolean.getBoolean("load.test.fast");
        List<Config> all = List.of(
            new Config(T2_I10_None.class,  2,  10, false),
            new Config(T4_I10_None.class,  4,  10, false),
            new Config(T8_I10_None.class,  8,  10, false),
            new Config(T2_I100_None.class, 2, 100, false),
            new Config(T4_I100_None.class, 4, 100, false),
            new Config(T8_I100_None.class, 8, 100, false),
            new Config(T2_I10_All.class,   2,  10, true),
            new Config(T4_I10_All.class,   4,  10, true),
            new Config(T8_I10_All.class,   8,  10, true),
            new Config(T2_I100_All.class,  2, 100, true),
            new Config(T4_I100_All.class,  4, 100, true),
            new Config(T8_I100_All.class,  8, 100, true)
        );
        if (fast) {
            return all.stream().filter(c -> c.threads() <= 4 && c.invocations() <= 10);
        }
        return all.stream();
    }

    @ParameterizedTest(name = "t={1} i={2} detectAll={3}")
    @MethodSource("configs")
    void measure(Config config) {
        System.out.printf("  Measuring: threads=%d invocations=%d detectAll=%s%n",
                config.threads(), config.invocations(), config.detectAll());

        // Two warmup rounds to let JIT stabilize
        for (int w = 0; w < 2; w++) {
            EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(config.target()))
                    .execute();
        }

        // Three measurement rounds — take the median
        long[] samples = new long[3];
        for (int m = 0; m < 3; m++) {
            long start = System.nanoTime();
            EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(config.target()))
                    .execute();
            samples[m] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        long medianMs = samples[1] / 1_000_000;
        long throughput = medianMs > 0 ? (config.invocations() * 1000L) / medianMs : Long.MAX_VALUE;

        rows.add(String.format("%d,%d,%s,%d,%d",
                config.threads(), config.invocations(), config.detectAll(), medianMs, throughput));
    }

    @AfterAll
    static void writeResults() throws IOException {
        String version = System.getProperty("async.test.version", "unknown");
        String outputDir = System.getProperty("load.test.output.dir",
                System.getProperty("java.io.tmpdir"));

        Path out = Paths.get(outputDir, "throughput.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            w.printf("# async-test-lib load-test throughput sweep%n");
            w.printf("# version=%s date=%s jdk=%s%n",
                    version, Instant.now().toString(),
                    System.getProperty("java.version"));
            w.println("threads,invocations,detectAll,medianMs,throughputRoundsPerSec");
            rows.forEach(w::println);
        }
        writeEnvFile(version, outputDir);
        System.out.println("Throughput results written to: " + out);
    }

    private static void writeEnvFile(String version, String outputDir) throws IOException {
        Path env = Paths.get(outputDir, "env.txt");
        if (Files.exists(env)) return; // written by first test class; don't overwrite

        String commit = execQuiet("git", "rev-parse", "--short", "HEAD");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(env,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            w.printf("Library version: %s%n", version);
            w.printf("HEAD commit: %s%n", commit.isBlank() ? "N/A" : commit.trim());
            w.printf("Date (UTC): %s%n", Instant.now());
            w.printf("OS: %s %s%n",
                    System.getProperty("os.name"), System.getProperty("os.version"));
            w.printf("JDK: %s (%s)%n",
                    System.getProperty("java.version"), System.getProperty("java.vendor"));
            w.printf("CPUs: %d%n", Runtime.getRuntime().availableProcessors());
        }
    }

    private static String execQuiet(String... cmd) {
        try {
            return new String(Runtime.getRuntime().exec(cmd).getInputStream().readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }

    // ── Target classes (excluded from direct JUnit discovery via *$* build filter) ─────────────

    public static class T2_I10_None {
        @AsyncTest(threads = 2, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T4_I10_None {
        @AsyncTest(threads = 4, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T8_I10_None {
        @AsyncTest(threads = 8, invocations = 10, detectAll = false) public void run() {}
    }
    public static class T2_I100_None {
        @AsyncTest(threads = 2, invocations = 100, detectAll = false) public void run() {}
    }
    public static class T4_I100_None {
        @AsyncTest(threads = 4, invocations = 100, detectAll = false) public void run() {}
    }
    public static class T8_I100_None {
        @AsyncTest(threads = 8, invocations = 100, detectAll = false) public void run() {}
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
    public static class T2_I100_All {
        @AsyncTest(threads = 2, invocations = 100, detectAll = true) public void run() {}
    }
    public static class T4_I100_All {
        @AsyncTest(threads = 4, invocations = 100, detectAll = true) public void run() {}
    }
    public static class T8_I100_All {
        @AsyncTest(threads = 8, invocations = 100, detectAll = true) public void run() {}
    }
}
