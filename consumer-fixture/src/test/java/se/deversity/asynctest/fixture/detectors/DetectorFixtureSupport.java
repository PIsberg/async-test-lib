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
     * Asserts that none of the named detectors reported during this test class.
     *
     * <p>The other direction, and not a weaker one. Several fixtures here deliberately
     * demonstrate the <em>correct</em> pattern - locks released, {@code remove()} called, an
     * interrupt restored, a retry loop that makes progress - and for those the detector staying
     * silent is the behaviour worth pinning. Asserting a finding instead would demand that
     * correct code be flagged.
     *
     * <p>Use {@link #assertAllReported} where the fixture demonstrates the hazard, this where it
     * demonstrates the fix. A fixture that does neither is asserting nothing.
     *
     * @param findings the collector opened in {@code @BeforeAll}
     * @param detectorNames simple class names that must not appear in the findings
     */
    static void assertNoneReported(AsyncFindings findings, String... detectorNames) {
        List<String> noisy = new ArrayList<>();
        for (String detector : detectorNames) {
            List<?> violations = findings.violationsFrom(detector);
            if (!violations.isEmpty()) {
                noisy.add(detector + " -> " + violations);
            }
        }
        if (noisy.isEmpty()) {
            return;
        }
        throw new AssertionError(
                "These detectors reported against a fixture that demonstrates the correct "
                        + "pattern, not the hazard:\n  " + String.join("\n  ", noisy)
                        + "\n\nThat is a false positive reaching a consumer: the code above "
                        + "releases its locks, removes its ThreadLocal, restores its interrupt "
                        + "or otherwise does the right thing, and the detector flagged it "
                        + "anyway. Fix the detector, or - if the fixture stopped demonstrating "
                        + "the correct pattern - fix the fixture and say so.");
    }


    /**
     * Runs {@code registration} exactly once per round, holding the other workers until it has.
     *
     * <p>Registration is a setup step, not a per-thread one, but an {@code @AsyncTest} body runs
     * once per thread - so the natural way to write a fixture calls {@code registerX} from every
     * worker. Several detectors install a fresh state object on each registration, and resolve a
     * later access to the first matching entry they happen to iterate. Two registrations for one
     * shared subject can therefore scatter the workers' accesses across entries that each saw a
     * single thread, and the "more than one thread touched this" findings never reach two.
     *
     * <p>Whether that happens depends on interleaving and on identity hash codes, which differ
     * per JVM run: fixtures written this way passed locally and on most CI legs and failed on
     * one, with a different detector each time.
     *
     * <p>Only needed where the registered subject is shared across the round. A subject
     * allocated per invocation is a different object per worker and registering it per worker is
     * correct.
     *
     * @param key identifies the registration; any stable string unique within the fixture class
     * @param registration the {@code registerX} call to perform once
     */
    static void registerOnce(String key, Runnable registration) {
        java.util.concurrent.CountDownLatch gate =
            REGISTRATION_GATES.computeIfAbsent(key, k -> new java.util.concurrent.CountDownLatch(1));
        if (REGISTERED.add(key)) {
            registration.run();
            gate.countDown();
            return;
        }
        try {
            gate.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Which registrations have been performed, by key. */
    private static final Set<String> REGISTERED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One gate per registration, so late workers wait rather than racing it. */
    private static final java.util.Map<String, java.util.concurrent.CountDownLatch>
        REGISTRATION_GATES = new java.util.concurrent.ConcurrentHashMap<>();

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
