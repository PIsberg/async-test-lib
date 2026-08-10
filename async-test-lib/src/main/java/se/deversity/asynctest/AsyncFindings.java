package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Collects detector findings so a test can assert on them.
 *
 * <p>Findings reach user code through {@link AsyncTestListener}, whose string callbacks hand
 * over a report written for humans. Asserting "this run reported a race" therefore meant
 * substring-matching prose. This collector records the structured {@link Violation} for every
 * finding instead, and turns the common assertions into one call each.
 *
 * <h2>Usage</h2>
 * Detectors analyse after the last round, so a finding cannot be observed from inside the test
 * body. Collect around the run, and assert after it:
 *
 * <pre>{@code
 * class CounterTest {
 *     static AsyncFindings findings;
 *
 *     @BeforeAll static void collect()  { findings = AsyncFindings.collect(); }
 *     @AfterAll  static void release()  { findings.close(); }
 *
 *     @AsyncTest(threads = 4, invocations = 50, failOn = FailOn.NONE)
 *     void increments() { counter.increment(); }
 *
 *     @AfterAll
 *     static void theRaceIsReported() {
 *         findings.assertReported("RaceConditionDetector");
 *     }
 * }
 * }</pre>
 *
 * <p>{@code failOn = FailOn.NONE} is what makes the findings assertable rather than fatal: with
 * the default threshold the run fails before the assertion is reached.
 *
 * <p><strong>Detector names</strong> are simple class names, as the runner keys its reports
 * ({@code "RaceConditionDetector"}, {@code "BusyWaitDetector"}). Matching is case-insensitive
 * and accepts any substring of the name, so {@code "RaceCondition"} matches.
 *
 * <p><strong>Thread safety:</strong> findings arrive from the runner's threads; the collector
 * records into a {@link CopyOnWriteArrayList} and every accessor returns an immutable snapshot.
 *
 * @see AsyncTestListener#onViolation(Violation)
 * @since 1.9.0
 */
@AIContract(reason = "Public assertion API for detector findings. collect(), violations() and the assertXxx methods are called directly from user test code — signatures and matching semantics must not change without a major version bump.")
@AIPublicAPI
@API(status = Status.EXPERIMENTAL)
public final class AsyncFindings implements AsyncTestListener, AutoCloseable {

    private final List<Violation> violations = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * Creates a collector without registering it. Prefer {@link #collect()}; use this only when
     * registering by hand, e.g. to keep one collector across a whole class.
     */
    public AsyncFindings() { /* registration is the caller's business */ }

    /**
     * Creates a collector and registers it with {@link AsyncTestListenerRegistry}.
     *
     * <p>The registry is JVM-wide, so the collector must be closed — either with
     * try-with-resources or from an {@code @AfterAll} — or it keeps recording findings from
     * every later test in the same JVM.
     *
     * @return a registered collector
     */
    public static AsyncFindings collect() {
        AsyncFindings findings = new AsyncFindings();
        AsyncTestListenerRegistry.register(findings);
        return findings;
    }

    @Override
    public void onViolation(Violation violation) {
        if (!closed && violation != null) {
            violations.add(violation);
        }
    }

    /**
     * {@return every finding recorded so far, oldest first, as an immutable snapshot}
     */
    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    /**
     * Returns the findings reported by one detector.
     *
     * @param detectorName the detector's simple class name, or any substring of it
     * @return the matching findings, oldest first, as an immutable snapshot
     */
    public List<Violation> violationsFrom(String detectorName) {
        List<Violation> matches = new ArrayList<>();
        for (Violation v : violations) {
            if (matches(v, detectorName)) {
                matches.add(v);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Asserts that the given detector reported at least one finding.
     *
     * @param detectorName the detector's simple class name, or any substring of it
     * @throws AssertionError if it reported nothing; the message lists what was reported instead
     */
    public void assertReported(String detectorName) {
        if (violationsFrom(detectorName).isEmpty()) {
            throw new AssertionError("Expected a finding from detector '" + detectorName
                    + "', but " + describeReported());
        }
    }

    /**
     * Asserts that the given detector reported at least one finding at the given severity.
     *
     * @param detectorName the detector's simple class name, or any substring of it
     * @param severity     the severity the finding must carry
     * @throws AssertionError if no such finding was reported
     */
    public void assertReported(String detectorName, IssueSeverity severity) {
        for (Violation v : violations) {
            if (matches(v, detectorName) && v.severity() == severity) {
                return;
            }
        }
        throw new AssertionError("Expected a " + severity + " finding from detector '"
                + detectorName + "', but " + describeReported());
    }

    /**
     * Asserts that the given detector reported nothing.
     *
     * @param detectorName the detector's simple class name, or any substring of it
     * @throws AssertionError if it reported anything; the message carries the first such finding
     */
    public void assertNotReported(String detectorName) {
        List<Violation> matches = violationsFrom(detectorName);
        if (!matches.isEmpty()) {
            throw new AssertionError("Expected no finding from detector '" + detectorName
                    + "', but got " + matches.size() + ": " + matches.get(0).message());
        }
    }

    /**
     * Asserts that no detector reported anything.
     *
     * @throws AssertionError if any finding was recorded; the message lists them
     */
    public void assertNone() {
        if (!violations.isEmpty()) {
            throw new AssertionError("Expected no detector findings, but " + describeReported());
        }
    }

    /**
     * Discards everything recorded so far, so one registered collector can be reused per test
     * without carrying the previous test's findings into the next assertion.
     */
    public void clear() {
        violations.clear();
    }

    /**
     * Unregisters the collector and stops recording. Idempotent.
     *
     * <p>Recorded findings stay readable after closing, so assertions can run afterwards.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        AsyncTestListenerRegistry.unregister(this);
    }

    private static boolean matches(Violation violation, String detectorName) {
        if (detectorName == null || detectorName.isBlank()) return false;
        String recorded = violation.detector().toLowerCase(Locale.ROOT);
        String wanted = detectorName.toLowerCase(Locale.ROOT);
        return recorded.contains(wanted);
    }

    private String describeReported() {
        if (violations.isEmpty()) {
            return "nothing was reported";
        }
        return violations.size() + " finding(s) were reported: "
                + violations.stream()
                        .map(v -> v.detector() + " (" + v.severity() + ")")
                        .distinct()
                        .collect(Collectors.joining(", "));
    }
}
