package se.deversity.asynctest;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dogfoods {@link ListenerRegistryCore} with {@code @AsyncTest}: a finding fired while a snapshot
 * is being restored must still reach the listeners that are in the set both before and after.
 *
 * <p>Why this exists. {@code snapshot()} and {@code restoreSnapshot()} are the documented way to
 * scope listeners around a block, so a {@code @BeforeEach} / {@code @AfterEach} pair restoring a
 * snapshot while worker threads are still firing is the ordinary case, not an exotic one.
 * Restoring used to clear the set and then refill it, which is two mutations, and a fire landing
 * between them iterated an empty registry. Nothing threw. The listener simply never heard about a
 * finding that had been reported, which is the same failure direction as #439: the evidence is
 * gone and the report is quietly short.
 *
 * <p>Why this needed the split first. The registry is JVM-wide, so a test firing synthetic
 * violations at it would deliver them to the listeners of the run driving the test, including
 * whatever report the run is assembling. Each round here gets its own {@link ListenerRegistryCore}
 * that nothing else can see.
 *
 * <p>Why the loop. The window between two adjacent writes is a few nanoseconds wide, so one fire
 * against one restore would almost never land in it. Every worker runs
 * {@link #ITERATIONS} operations per round, three of the four firing and one restoring, which puts
 * hundreds of restores and hundreds of fires against each other in every round.
 *
 * <p>Restoring the set the round started with makes the expected outcome exact rather than
 * statistical: the listener is in the set before and after every restore, so a correct registry
 * delivers every single fire and the assertion is an equality, not a bound.
 */
class ListenerRegistryRestoreDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 30;
    private static final int ITERATIONS = 200;

    /** One worker per round restores; the rest fire. */
    private static final int RESTORING_WORKER = 0;
    private static final int FIRING_WORKERS = THREADS - 1;

    private static final int EXPECTED = FIRING_WORKERS * ITERATIONS * ROUNDS;

    private static final class CountingListener implements AsyncTestListener {
        private final AtomicInteger heard = new AtomicInteger();

        @Override
        public void onViolation(Violation violation) {
            heard.incrementAndGet();
        }
    }

    private record Round(ListenerRegistryCore core, CountingListener listener,
                         List<AsyncTestListener> snapshot) {
    }

    private static final Map<Integer, Round> ROUNDS_BY_INDEX = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger FIRED = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 30_000)
    void aFindingFiredDuringARestoreIsStillHeard() {
        int ticket = SEQUENCE.getAndIncrement();
        int round = ticket / THREADS;
        int worker = ticket % THREADS;

        Round current = ROUNDS_BY_INDEX.computeIfAbsent(round, ignored -> {
            ListenerRegistryCore core = new ListenerRegistryCore();
            CountingListener listener = new CountingListener();
            core.register(listener);
            return new Round(core, listener, core.snapshot());
        });

        if (worker == RESTORING_WORKER) {
            for (int i = 0; i < ITERATIONS; i++) {
                // Puts back the set the round started with, so the listener is present before and
                // after. Only a restore that is not a single write can hide it.
                current.core().restore(current.snapshot());
            }
            return;
        }

        for (int i = 0; i < ITERATIONS; i++) {
            current.core().fireViolation(violation(round, worker, i));
            FIRED.incrementAndGet();
        }
    }

    private static Violation violation(int round, int worker, int index) {
        return new Violation("DogfoodDetector", IssueSeverity.HIGH,
                "round " + round + " worker " + worker + " finding " + index,
                List.of(), Map.of(), Instant.now());
    }

    @AfterAll
    static void everyFindingSurvivedTheRestores() {
        assertEquals(ROUNDS, ROUNDS_BY_INDEX.size(), "rounds shared a registry");
        assertEquals(EXPECTED, FIRED.get(), "the workers did not fire what this test assumes");

        int heard = 0;
        int shortRounds = 0;
        for (Round round : ROUNDS_BY_INDEX.values()) {
            int perRound = round.listener().heard.get();
            if (perRound != FIRING_WORKERS * ITERATIONS) {
                shortRounds++;
            }
            heard += perRound;
        }

        assertEquals(EXPECTED, heard,
                shortRounds + " of " + ROUNDS + " rounds lost findings: a violation fired while a "
                        + "snapshot was being restored reached nobody, so a detector that did "
                        + "report is missing from the report and nothing said so");
    }
}
