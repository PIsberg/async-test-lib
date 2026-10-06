package se.deversity.asynctest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunOutcomes} counts what the workers of a run did and asserts on the totals after it.
 *
 * <p>The checks it replaces were each a static counter and a hand-written {@code @AfterAll}, and
 * one of them, {@code LicenseGuard}'s old {@code cacheIsThreadSafe}, asserted something that held
 * whatever the code did (#904). The concurrent half here records from every worker of every round
 * and requires exact totals, so a lost update in the collector fails it.
 */
@ConcurrencyTestFor(RunOutcomes.class)
class RunOutcomesTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 100;

    private static final RunOutcomes CONCURRENT = new RunOutcomes();
    private static final AtomicLong IDS = new AtomicLong();

    /** One new event per round, so all its workers race to register it, 100 times a run. */
    private static final Set<String> ROUND_EVENTS = ConcurrentHashMap.newKeySet();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 60_000)
    void everyWorkerRecords() {
        CONCURRENT.record("ran");
        String round = "round " + AsyncTestContext.replaySeed();
        ROUND_EVENTS.add(round);
        CONCURRENT.record(round);
        CONCURRENT.recordValue(IDS.incrementAndGet());
        if (AsyncTestContext.replaySeed() == Long.MIN_VALUE) {
            CONCURRENT.record("never");
        }
    }

    @AfterAll
    static void theTotalsAreExact() {
        CONCURRENT.assertCount("ran", THREADS * ROUNDS);
        for (String round : ROUND_EVENTS) {
            CONCURRENT.assertCount(round, THREADS);
        }
        CONCURRENT.assertAtMostOnce("never");
        CONCURRENT.assertDistinct();
        assertEquals(0, CONCURRENT.count("never"));
    }

    @Test
    void exactlyOncePassesOnOneAndFailsOnNoneOrTwo() {
        RunOutcomes outcomes = new RunOutcomes();
        AssertionError none = assertThrows(AssertionError.class, () -> outcomes.assertExactlyOnce("won"));
        assertTrue(none.getMessage().contains("'won' happened 0 times"), none.getMessage());

        outcomes.record("won");
        assertDoesNotThrow(() -> outcomes.assertExactlyOnce("won"));

        outcomes.record("won");
        AssertionError twice = assertThrows(AssertionError.class, () -> outcomes.assertExactlyOnce("won"));
        assertTrue(twice.getMessage().contains("'won' happened 2 times, expected exactly 1"), twice.getMessage());
        assertTrue(twice.getMessage().contains(Thread.currentThread().getName()),
                "the message names the threads that recorded it: " + twice.getMessage());
    }

    @Test
    void atMostOnceAllowsNoneButNotTwo() {
        RunOutcomes outcomes = new RunOutcomes();
        assertDoesNotThrow(() -> outcomes.assertAtMostOnce("gate"));
        outcomes.record("gate");
        assertDoesNotThrow(() -> outcomes.assertAtMostOnce("gate"));
        outcomes.record("gate");
        AssertionError e = assertThrows(AssertionError.class, () -> outcomes.assertAtMostOnce("gate"));
        assertTrue(e.getMessage().contains("expected at most 1"), e.getMessage());
    }

    @Test
    void assertCountComparesTheExactTotal() {
        RunOutcomes outcomes = new RunOutcomes();
        outcomes.record("hit");
        outcomes.record("hit");
        assertEquals(2, outcomes.count("hit"));
        assertDoesNotThrow(() -> outcomes.assertCount("hit", 2));
        AssertionError e = assertThrows(AssertionError.class, () -> outcomes.assertCount("hit", 3));
        assertTrue(e.getMessage().contains("'hit' happened 2 times, expected 3"), e.getMessage());
    }

    @Test
    void assertDistinctNamesTheDuplicatedValues() {
        RunOutcomes outcomes = new RunOutcomes();
        outcomes.recordValue("a");
        outcomes.recordValue("b");
        assertDoesNotThrow(outcomes::assertDistinct);
        outcomes.recordValue("a");
        AssertionError e = assertThrows(AssertionError.class, outcomes::assertDistinct);
        assertTrue(e.getMessage().contains("a (2 times)"), e.getMessage());
        assertTrue(!e.getMessage().contains("b ("), "only duplicates are listed: " + e.getMessage());
    }

    @Test
    void nullEventsAndValuesAreRefused() {
        RunOutcomes outcomes = new RunOutcomes();
        assertThrows(NullPointerException.class, () -> outcomes.record(null));
        assertThrows(NullPointerException.class, () -> outcomes.recordValue(null));
    }
}
