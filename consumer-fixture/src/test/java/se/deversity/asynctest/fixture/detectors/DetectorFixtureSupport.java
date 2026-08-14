package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncFindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * <p>Every detector makes this claim. Seven of them could not until 1.7.0 added their
 * accessors: {@code DEADLOCKS}, {@code VISIBILITY}, {@code LIVELOCKS},
 * {@code RACE_CONDITIONS}, {@code THREAD_LOCAL_LEAKS}, {@code BUSY_WAITING} and
 * {@code INTERRUPT_MISHANDLING} were reachable only through {@code AsyncTestContext}'s
 * internal {@code shared*} methods, which a consumer must not call. Those fixtures used to
 * assert the weaker "this body is running inside a configured round"; they no longer need to.
 */
final class DetectorFixtureSupport {

    private DetectorFixtureSupport() {
    }

    /**
     * Asserts that every named detector produced at least one finding during this test class.
     *
     * <p>The companion to {@link #reachable}, and the assertion that was missing. Reachability
     * proves a consumer can obtain the detector; this proves the detector, fed by consumer code
     * through its public recording API, produced a finding that came back out through
     * {@link AsyncFindings} — the same channel the printed report and the {@code failOn} gate
     * are built on. Without it a fixture passes with the detector deleted.
     *
     * <p>Call from {@code @AfterAll}: detectors analyse after the last round, so no finding is
     * observable from inside a test body, and JUnit does not order {@code @Test} against
     * {@code @AsyncTest} methods.
     *
     * @param findings the collector opened in {@code @BeforeAll}
     * @param detectorNames simple class names, e.g. {@code "SharedChecksumDetector"}
     */
    static void assertAllReported(AsyncFindings findings, String... detectorNames) {
        List<String> silent = new ArrayList<>();
        for (String detector : detectorNames) {
            if (findings.violationsFrom(detector).isEmpty()) {
                silent.add(detector);
            }
        }
        if (silent.isEmpty()) {
            return;
        }
        Set<String> reported = new TreeSet<>();
        findings.violations().forEach(v -> reported.add(v.detector()));
        throw new AssertionError(
                "These detectors were enabled and fed by the fixture above, and reported "
                        + "nothing:\n  " + String.join("\n  ", silent)
                        + "\n\nA fixture that runs the hazard but asserts only reachability "
                        + "cannot fail if the detector stops detecting - which is what every "
                        + "fixture in this package did before. Either the recording call is "
                        + "missing or wrong for this detector, or the workload does not "
                        + "actually share the instance across threads.\n\nDetectors that did "
                        + "report in this class: " + (reported.isEmpty() ? "(none)" : reported));
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
