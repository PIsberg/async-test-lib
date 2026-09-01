package se.deversity.asynctest;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Dogfoods {@link AsyncFindings} with {@code @AsyncTest}.
 *
 * <p>Why this exists: findings arrive from the racing workers themselves, and users read them from
 * a different thread while more are still landing. Two properties hold that together and neither
 * is reachable from a straight-line test. Every violation handed to the collector has to survive,
 * because a dropped one turns into an {@code assertReported} that fails on a detector that did in
 * fact fire. And {@link AsyncFindings#violations()} has to hand back a stable snapshot while writes
 * continue, rather than a list that can be seen mid-append.
 *
 * <p>The collector is built with the constructor that does not register, so nothing here reaches
 * the JVM-wide listener registry or the reporting of the run driving it.
 *
 * <p>Each violation carries a unique message, so the closing count is checked two ways: the size,
 * which catches a drop, and the number of distinct messages, which catches a slot written twice.
 */
class AsyncFindingsDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 200;
    private static final int EXPECTED = THREADS * ROUNDS;

    /** Deliberately not {@code collect()}: registering would feed the enclosing run's reporting. */
    private static final AsyncFindings FINDINGS = new AsyncFindings();

    private static final AtomicInteger NEXT_TAG = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 20_000)
    void collectorTakesEveryViolationWhileItIsBeingRead() {
        int tag = NEXT_TAG.incrementAndGet();
        FINDINGS.onViolation(new Violation(
                "DogfoodDetector" + (tag % 4),
                IssueSeverity.HIGH,
                "dogfood violation " + tag,
                List.of(),
                Map.of(),
                Instant.now()));

        // Read back while the other workers are still writing. This must neither throw nor hand
        // back a list that is still being appended to.
        List<Violation> snapshot = FINDINGS.violations();
        assertFalse(snapshot.isEmpty(), "a read taken after this worker's own write came back empty");

        // Filtering walks the same live collection, which is where a weakly consistent iterator
        // would surface.
        assertFalse(FINDINGS.violationsFrom("DogfoodDetector").isEmpty(),
                "filtering lost every violation while writes were in flight");
    }

    @AfterAll
    static void everyViolationSurvivedAndCloseStopsRecording() {
        List<Violation> all = FINDINGS.violations();

        assertEquals(EXPECTED, all.size(),
                "the collector holds fewer violations than the workers handed it");
        assertEquals(EXPECTED, all.stream().map(Violation::message).distinct().count(),
                "two workers wrote the same slot, so a violation was overwritten rather than added");

        FINDINGS.close();
        int afterClose = FINDINGS.violations().size();
        FINDINGS.onViolation(new Violation(
                "DogfoodDetectorAfterClose", IssueSeverity.HIGH, "must not be recorded",
                List.of(), Map.of(), Instant.now()));
        assertEquals(afterClose, FINDINGS.violations().size(),
                "close() did not stop the recording, so the collector keeps taking findings from "
                        + "every later test in this JVM");

        // Closing is documented to keep what was already recorded readable.
        assertEquals(EXPECTED, afterClose, "close() discarded findings that were already recorded");
        assertSame(IssueSeverity.HIGH, all.get(0).severity(), "the recorded violation was mangled");
    }
}
