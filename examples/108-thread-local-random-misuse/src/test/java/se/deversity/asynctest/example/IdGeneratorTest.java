package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for IdGenerator.
 *
 * ========================================================================
 * DETECTOR: ThreadLocalRandomMisuseDetector
 * ========================================================================
 *
 * THE BUG:
 * IdGenerator caches the result of ThreadLocalRandom.current() in a final field at
 * construction time. ThreadLocalRandom.current() returns the generator owned by the
 * calling thread; caching that reference and using it from other threads defeats the
 * per-thread isolation the class depends on and corrupts/biases its output.
 *
 * WHY @Test PASSES:
 * A single-threaded test constructs and uses the generator on one and the same thread,
 * so the cached reference is never used from a foreign thread. Output looks fine.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 worker threads sharing one IdGenerator, the first thread registers as the
 * owner (obtaining thread) of the cached RNG, and the remaining threads' uses of that
 * same reference are flagged as cross-thread misuse by ThreadLocalRandomMisuseDetector.
 *
 * FIX:
 * Never store ThreadLocalRandom.current(). Call it afresh per use on each thread,
 * e.g. ThreadLocalRandom.current().nextLong(), so every thread uses its own generator.
 */
class IdGeneratorTest {

    private IdGenerator generator;

    @BeforeEach
    void setUp() {
        // AsyncTestContext is NOT available here (no active test context), so the
        // detector is not called in @BeforeEach. The cached RNG reference is captured
        // by ThreadLocalRandom.current() at construction time.
        generator = new IdGenerator();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testNextId_singleThread_runsWithoutException() {
        assertDoesNotThrow(() -> generator.nextId());
    }

    @Test
    void testGenerator_isNotNull() {
        assertNotNull(generator);
    }

    @Test
    void testNextId_calledTwice_runsWithoutException() {
        assertDoesNotThrow(() -> {
            generator.nextId();
            generator.nextId();
        });
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the cached ThreadLocalRandom bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads concurrently use the same cached ThreadLocalRandom reference.
     * The first worker thread registers as the obtaining (owner) thread; the other
     * seven threads' uses of the same reference are flagged as cross-thread misuse
     * by ThreadLocalRandomMisuseDetector.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: call ThreadLocalRandom.current() per use; never cache it
     */
    @Disabled("Remove @Disabled to see the bug detected by ThreadLocalRandomMisuseDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThreadLocalRandomMisuse = true)
    void test_concurrent_detectsCachedReferenceUsedAcrossThreads() {
        Thread thread = Thread.currentThread();

        // The generator captured current() at construction time on the test-runner's
        // setup thread. recordObtain uses computeIfAbsent, so the first worker thread
        // wins as the owner; here every worker thread uses that same cached reference:
        AsyncTestContext.threadLocalRandomMisuseDetector()
                .recordObtain(generator.getRng(), "cached-rng", thread);
        AsyncTestContext.threadLocalRandomMisuseDetector()
                .recordUse(generator.getRng(), thread);

        generator.nextId();
    }
}
