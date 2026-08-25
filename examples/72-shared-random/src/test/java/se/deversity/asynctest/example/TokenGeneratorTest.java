package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TokenGenerator.
 *
 * ========================================================================
 * DETECTOR: SharedRandomDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * TokenGenerator uses a single static java.util.Random shared across all
 * threads. While Random is individually thread-safe (CAS-based seed update),
 * all threads contend on the same AtomicLong. Under high concurrency throughput
 * degrades significantly. SharedRandomDetector flags the instance as accessed
 * from multiple threads simultaneously.
 *
 * WHY @Test PASSES:
 * A single thread generates tokens without any contention. The output is a
 * correctly formed alphanumeric string of the requested length.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads all call generateToken() concurrently, each invoking RANDOM.nextInt()
 * on the shared instance. SharedRandomDetector records which threads access each
 * Random instance and reports instances accessed from more than one thread.
 *
 * DETECTORS TRIGGERED:
 *   SharedRandomDetector — primary: detects shared Random accessed by multiple threads
 *
 * FIX: use ThreadLocalRandom.current().nextInt() to eliminate seed contention.
 */
class TokenGeneratorTest {

    private TokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TokenGenerator();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testGenerateToken_singleThread_correctLength() {
        String token = generator.generateToken(16);
        assertEquals(16, token.length(), "Token must have exactly 16 characters");
    }

    @Test
    void testGenerateToken_twoTokens_areDistinct() {
        String t1 = generator.generateToken(32);
        String t2 = generator.generateToken(32);
        // Statistically extremely unlikely to be equal with 32 chars
        assertNotEquals(t1, t2, "Two generated tokens should almost certainly differ");
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes shared Random contention
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see shared Random detected by SharedRandomDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSharedRandom = true, failOn = FailOn.LOW)
    void testGenerateToken_concurrent_detectsSharedRandom() {
        // Register the shared static Random with the detector
        AsyncTestContext.sharedRandomMonitor()
                .registerRandom(TokenGenerator.getRandom(), "token-generator-random");

        // Record that this thread is accessing the shared Random
        AsyncTestContext.sharedRandomMonitor()
                .recordRandomAccess(TokenGenerator.getRandom(),
                        "token-generator-random", "nextInt");

        // Generate a token — internally calls RANDOM.nextInt() on the shared instance
        String token = generator.generateToken(16);

        assertNotNull(token, "Token must not be null");
        assertEquals(16, token.length(), "Token must have correct length");
    }
}
