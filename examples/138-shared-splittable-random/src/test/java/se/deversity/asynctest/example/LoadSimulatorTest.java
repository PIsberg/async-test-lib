package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedSplittableRandomDetector;
import se.deversity.asynctest.example.service.LoadSimulator;

import java.util.Random;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for LoadSimulator.
 *
 * ========================================================================
 * DETECTOR: SharedSplittableRandomDetector
 *           (DetectorType.SHARED_SPLITTABLE_RANDOM)
 * ========================================================================
 *
 * SplittableRandom Javadoc: "Instances of SplittableRandom are not
 * thread-safe." The same holds for the JEP 356 java.util.random
 * implementations (L64X128MixRandom, Xoshiro256PlusPlus, ...). Their
 * state update is a plain read-modify-write; concurrent nextLong() calls
 * interleave it and silently corrupt the sequence. Unlike a shared
 * java.util.Random — thread-safe but contended — there is no exception
 * and no contention spike to notice, just randomness that stops being
 * random.
 *
 * THE BUG:
 *   - one static SplittableRandom drawn from by every worker thread
 *
 * THE FIX:
 *   - split() once per worker on the coordinating thread and hand each
 *     worker its own independent child generator
 *
 * SCOPE:
 *   - java.util.Random subclasses are deliberately not this detector's
 *     finding: Random belongs to SHARED_RANDOM, SecureRandom to
 *     SHARED_SECURE_RANDOM, ThreadLocalRandom to
 *     THREAD_LOCAL_RANDOM_MISUSE
 */
class LoadSimulatorTest {

    private static final long SEED = 42;

    private LoadSimulator simulator;
    private SharedSplittableRandomDetector detector;

    @BeforeEach
    void setUp() {
        simulator = new LoadSimulator(SEED);
        detector = new SharedSplittableRandomDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed shape. Each worker records only its own child
    // generator — one thread per instance, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void splitPerWorker_isClean() throws InterruptedException {
        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            SplittableRandom own = simulator.generatorForWorker();
            String name = "worker-" + i;
            workers[i] = new Thread(() -> {
                simulator.thinkTimeMillis(own);
                detector.recordAccess(own, name, "nextLong");
            }, name);
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "split()-per-worker must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy shape. Two workers draw from the same instance with no
    // lock held on it, so the guard-on-self probe finds nothing to excuse the
    // sharing and the detector reports it.
    // -----------------------------------------------------------------------

    @Test
    void sharedGeneratorAcrossWorkers_isDetected() throws InterruptedException {
        SplittableRandom shared = simulator.sharedGenerator();
        detector.registerGenerator(shared, "think-time");

        simulator.thinkTimeMillis(shared);
        detector.recordAccess(shared, "think-time", "nextLong");

        Thread worker = new Thread(() -> {
            simulator.thinkTimeMillis(shared);
            detector.recordAccess(shared, "think-time", "nextLong");
        }, "worker-1");
        worker.start();
        worker.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "A shared SplittableRandom must be flagged:\n" + report);
        assertTrue(report.toString().contains("think-time"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: scope. A shared java.util.Random is a real finding — but it is
    // SHARED_RANDOM's finding, not this detector's.
    // -----------------------------------------------------------------------

    @Test
    void javaUtilRandom_isAnotherDetectorsFinding() throws InterruptedException {
        Random random = new Random(SEED);
        detector.recordAccess(random, "plain-random", "nextInt");

        Thread worker = new Thread(() -> detector.recordAccess(random, "plain-random", "nextInt"), "worker-1");
        worker.start();
        worker.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "java.util.Random routes to SHARED_RANDOM, not this detector:\n" + report);
    }
}
