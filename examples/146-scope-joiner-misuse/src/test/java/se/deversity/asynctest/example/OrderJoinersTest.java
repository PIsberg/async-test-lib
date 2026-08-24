package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ScopeJoinerMisuseDetector;
import se.deversity.asynctest.example.service.OrderJoiners;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for OrderJoiners.
 *
 * ========================================================================
 * DETECTOR: ScopeJoinerMisuseDetector
 *           (DetectorType.SCOPE_JOINER_MISUSE)
 * ========================================================================
 *
 * A StructuredTaskScope.Joiner is called from two directions at once.
 * onComplete runs on whichever subtask thread finished, concurrently
 * with its peers. result() and the JDK 26 onTimeout() run on the owner.
 * A joiner accumulating into a plain ArrayList therefore races, and no
 * amount of correct scope usage removes it - the scope's lifecycle is
 * perfect, the joiner is the broken part.
 *
 * onTimeout() is what makes this urgent rather than theoretical. Before
 * JDK 26 a timeout simply threw, so a half-built accumulator was never
 * read. JEP 525 makes returning a partial result the recommended
 * pattern, which turns a latent race into the value the caller gets.
 *
 * THE BUG:
 *   - onComplete appends to an ArrayList from several subtask threads
 *   - onTimeout copies that list on the owner, mid-write
 *
 * THE FIX:
 *   - accumulate into a ConcurrentLinkedQueue (or hold a lock the joiner
 *     owns), so a snapshot is well defined whenever it is taken
 */
class OrderJoinersTest {

    private static final int SUBTASKS = 4;

    private ScopeJoinerMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ScopeJoinerMisuseDetector();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "latch never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting on latch", e);
        }
    }

    /**
     * Runs {@code SUBTASKS} completions against the joiner, holding every one inside
     * {@code onComplete} until all of them have written. That is the interleaving the JDK
     * produces when subtasks finish together, made deterministic so the example is not flaky.
     */
    private void completeConcurrently(Object joiner, Runnable accumulate) {
        var allIn = new CountDownLatch(SUBTASKS);
        var allWrote = new CountDownLatch(SUBTASKS);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < SUBTASKS; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                detector.recordOnCompleteEnter(joiner, Thread.currentThread());
                allIn.countDown();
                await(allIn);
                accumulate.run();
                detector.recordAccumulate(joiner, Thread.currentThread());
                allWrote.countDown();
                await(allWrote);
                detector.recordOnCompleteExit(joiner, Thread.currentThread(), false);
            }));
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted joining worker", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Part 1: the buggy joiner. Four subtask threads append to one ArrayList.
    // -----------------------------------------------------------------------

    @Test
    void anArrayListAccumulatorWrittenFromSubtaskThreads_isDetected() {
        var joiner = new OrderJoiners.Collecting();
        detector.recordJoinerBound(joiner, "orderJoiner", "scope-1", Thread.currentThread());

        completeConcurrently(joiner, () -> joiner.onComplete("order"));

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "four threads, one unguarded list:\n" + report);
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("inside onComplete at once")));

        var v = report.structuredViolations.stream()
                .filter(x -> "racyAccumulation".equals(x.attributes().get("issue")))
                .findFirst()
                .orElseThrow();
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(SUBTASKS, v.attributes().get("racingWriters"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the fixed joiner. Same interleaving, same recording calls, but
    // the accumulator is concurrent, so the detector is told to stop asking.
    // -----------------------------------------------------------------------

    @Test
    void aConcurrentQueueAccumulator_isClean() {
        var joiner = new OrderJoiners.ConcurrentCollecting();
        detector.recordJoinerBound(joiner, "orderJoiner", "scope-1", Thread.currentThread());
        detector.declareThreadSafe(joiner);

        completeConcurrently(joiner, () -> joiner.onComplete("order"));

        assertEquals(SUBTASKS, joiner.size(), "a concurrent queue keeps every result");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "a joiner whose state is concurrent has nothing to report:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the JDK 26 addition. onTimeout runs on the owner and is meant to
    // return a partial result - so it reads state the subtasks are mid-write on.
    // -----------------------------------------------------------------------

    @Test
    void onTimeoutReadingWhileSubtasksAreStillCompleting_isDetected() {
        var joiner = new OrderJoiners.Collecting();
        Thread owner = Thread.currentThread();
        detector.recordJoinerBound(joiner, "orderJoiner", "scope-1", owner);

        var inFlight = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                detector.recordOnCompleteEnter(joiner, Thread.currentThread());
                inFlight.countDown();
                await(release);
                joiner.onComplete("order");
                detector.recordOnCompleteExit(joiner, Thread.currentThread(), false);
            }));
        }
        await(inFlight);

        // The deadline expires here, with both subtasks still inside onComplete.
        joiner.onTimeout();
        detector.recordOnTimeout(joiner, owner);

        release.countDown();
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted joining worker", e);
            }
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "the fallback was built mid-write:\n" + report);
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("onComplete call(s) were still running")));
    }

    // -----------------------------------------------------------------------
    // Part 4: a joiner carries the run's state, so a second scope inherits the
    // first one's results. One instance per open() is the only correct usage.
    // -----------------------------------------------------------------------

    @Test
    void oneJoinerPassedToTwoScopes_isDetected() {
        var joiner = new OrderJoiners.ConcurrentCollecting();
        detector.recordJoinerBound(joiner, "orderJoiner", "scope-1", Thread.currentThread());
        joiner.onComplete("from-scope-1");
        detector.recordJoinerBound(joiner, "orderJoiner", "scope-2", Thread.currentThread());

        assertEquals(1, joiner.size(), "scope-2 starts with scope-1's result already in it");

        var report = detector.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("passed to 2 scopes")));
    }

    @Test
    void aFreshJoinerPerScope_isClean() {
        detector.recordJoinerBound(new OrderJoiners.ConcurrentCollecting(),
                "orderJoiner", "scope-1", Thread.currentThread());
        detector.recordJoinerBound(new OrderJoiners.ConcurrentCollecting(),
                "orderJoiner", "scope-2", Thread.currentThread());

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a fresh joiner per open() carries nothing:\n" + report);
    }
}
