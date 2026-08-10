package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SharedSplittableRandomDetector}: a SplittableRandom or JEP 356 generator
 * shared across threads is flagged, split-per-thread use is clean, and java.util.Random
 * subclasses are routed to their dedicated detectors instead.
 */
class SharedSplittableRandomDetectorTest {

    private SharedSplittableRandomDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedSplittableRandomDetector();
    }

    @Test
    void splittableRandomSharedAcrossTwoThreads_isFlagged() throws InterruptedException {
        SplittableRandom shared = new SplittableRandom(42);
        detector.registerGenerator(shared, "shared-ids");
        detector.recordAccess(shared, "shared-ids", "nextLong");

        Thread other = new Thread(
                () -> detector.recordAccess(shared, "shared-ids", "nextLong"), "other");
        other.start();
        other.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A SplittableRandom used from two threads must be flagged: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
        assertTrue(report.toString().contains("shared-ids"),
                "The report names the registered generator: " + report);
    }

    @Test
    void splitPerThread_isClean() throws InterruptedException {
        SplittableRandom parent = new SplittableRandom(42);
        detector.recordAccess(parent, "parent", "split");

        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            SplittableRandom own = parent.split();
            String name = "split-" + i;
            workers[i] = new Thread(() -> detector.recordAccess(own, name, "nextLong"), name);
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertFalse(detector.analyze().hasIssues(),
                "split() per thread is the designed use and must be clean");
    }

    @Test
    void jep356GeneratorSharedAcrossTwoThreads_isFlagged() throws InterruptedException {
        RandomGenerator shared = RandomGeneratorFactory.of("L64X128MixRandom").create(42);
        detector.recordAccess(shared, "lxm", "nextLong");

        Thread other = new Thread(() -> detector.recordAccess(shared, "lxm", "nextLong"), "other");
        other.start();
        other.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A shared L64X128MixRandom must be flagged: " + report);
        assertTrue(report.toString().contains("L64X128MixRandom"),
                "The report carries the generator type: " + report);
    }

    @Test
    void javaUtilRandom_isRoutedElsewhere() throws InterruptedException {
        Random random = new Random(42);
        detector.registerGenerator(random, "plain-random");
        detector.recordAccess(random, "plain-random", "nextInt");

        Thread other = new Thread(() -> detector.recordAccess(random, "plain-random", "nextInt"), "other");
        other.start();
        other.join();

        assertFalse(detector.analyze().hasIssues(),
                "java.util.Random is thread-safe and belongs to SHARED_RANDOM, not this detector");
    }

    @Test
    void threadLocalRandom_isRoutedElsewhere() throws InterruptedException {
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        detector.recordAccess(tlr, "tlr", "nextInt");

        Thread other = new Thread(() -> detector.recordAccess(tlr, "tlr", "nextInt"), "other");
        other.start();
        other.join();

        assertFalse(detector.analyze().hasIssues(),
                "ThreadLocalRandom extends Random and belongs to THREAD_LOCAL_RANDOM_MISUSE");
    }

    @Test
    void singleThreadUse_isClean() {
        SplittableRandom own = new SplittableRandom(42);
        detector.recordAccess(own, "own", "nextLong");
        detector.recordAccess(own, "own", "nextInt");

        assertFalse(detector.analyze().hasIssues(), "Single-thread use must be clean");
    }

    @Test
    void nullGenerator_isIgnored() {
        detector.registerGenerator(null, "null-generator");
        detector.recordAccess(null, "null-generator", "nextLong");

        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void analyze_isIdempotent() throws InterruptedException {
        SplittableRandom shared = new SplittableRandom(42);
        detector.recordAccess(shared, "shared", "nextLong");
        Thread other = new Thread(() -> detector.recordAccess(shared, "shared", "nextLong"), "t2");
        other.start();
        other.join();

        String first = detector.analyze().toString();
        String second = detector.analyze().toString();
        assertEquals(first, second, "analyze() must be idempotent on quiescent state");
    }
}
