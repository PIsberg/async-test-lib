package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTestContext;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared helpers for the per-detector consumer fixtures.
 *
 * <p>Every fixture in this package follows the same shape: enable exactly one
 * {@link se.deversity.asynctest.DetectorType} through {@code @AsyncTest(includes = {...})},
 * prove the detector is reachable from consumer code, then run a small workload of the kind
 * that detector watches.
 *
 * <p>The reachability check is the load-bearing assertion. Every
 * {@code AsyncTestContext.xxxDetector()} accessor routes through an internal {@code require}
 * that throws {@link IllegalStateException} when the detector is disabled for the current
 * round — so a non-throwing, non-null accessor call inside an {@code includes}-scoped round
 * proves three things at once:
 *
 * <ol>
 *   <li>the {@code DetectorType} constant resolves to a real detector in the registry
 *       (not a dangling enum value),</li>
 *   <li>{@code includes} actually enabled it rather than silently falling through, and</li>
 *   <li>the accessor is on the published artifact's public surface — the fixture compiles
 *       against the JAR, not against the library sources.</li>
 * </ol>
 *
 * <p>Seven detectors have no public per-detector accessor: {@code DEADLOCKS},
 * {@code VISIBILITY}, {@code LIVELOCKS}, {@code RACE_CONDITIONS}, {@code THREAD_LOCAL_LEAKS},
 * {@code BUSY_WAITING} and {@code INTERRUPT_MISHANDLING}. Their {@code shared*} accessors on
 * {@code AsyncTestContext} are documented as internal, so a consumer must not call them and
 * this fixture does not. Those cases use {@link #insideRound(long)} instead, which is a
 * weaker claim — the round is live and configured — and is honest about it.
 */
final class DetectorFixtureSupport {

    /** Seed pinned by every fixture that has to fall back to {@link #insideRound(long)}. */
    static final long PINNED_SEED = 990_099L;

    private DetectorFixtureSupport() {
    }

    /**
     * Asserts that a detector accessor is reachable for the currently running round.
     *
     * @param accessorName the accessor as a consumer would write it, e.g. {@code "lockLeakDetector()"}
     * @param accessor     the accessor itself, normally a method reference
     */
    static void reachable(String accessorName, Supplier<?> accessor) {
        Object detector = assertDoesNotThrow(accessor::get,
            "AsyncTestContext." + accessorName + " must be reachable inside an @AsyncTest round "
                + "that enables exactly this detector via includes = {...}");
        assertNotNull(detector,
            "AsyncTestContext." + accessorName + " returned null for an enabled detector");
    }

    /**
     * Fallback for the detectors with no public per-detector accessor: proves the body is
     * executing inside a live, configured {@code @AsyncTest} round by reading back the seed
     * the annotation pinned.
     */
    static void insideRound(long expectedSeed) {
        assertEquals(expectedSeed, AsyncTestContext.replaySeed(),
            "Fixture body must run inside an @AsyncTest round carrying the pinned replaySeed");
    }

    /** Burns a trivial amount of CPU without a timing dependency. */
    static int spin(int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            acc += i * 31;
        }
        return acc;
    }

    /** Sleeps without letting an interrupt escape the fixture, preserving the flag. */
    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
