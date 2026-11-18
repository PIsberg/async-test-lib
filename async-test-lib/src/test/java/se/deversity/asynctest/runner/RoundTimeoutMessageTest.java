package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a timeout says about why it happened (#1386).
 *
 * <p>{@code timeoutMs} is the budget for the whole run. A correct body that makes steady progress
 * and runs out of it used to fail with "Possible deadlock, starvation, or visibility issue" and a
 * cause saying a thread "may be stuck before the test body (e.g. broken barrier)", which sent the
 * reader looking for a hang that did not exist. The message builders are pinned branch by branch
 * here; {@link RoundTimeoutProgressTest} pins that the runner picks the right branch.
 */
class RoundTimeoutMessageTest {

    // ---- the round's own message ----

    @Test
    void aRoundWhoseWorkersWereInTheBodyDoesNotBlameTheBarrier() {
        String message = ConcurrencyRunner.roundTimeoutMessage(2, 0, 2, 6);

        assertTrue(message.contains("0/2 threads completed within 6ms"), message);
        assertFalse(message.contains("barrier"), "both workers got past the barrier: " + message);
    }

    @Test
    void aWorkerThatNeverPassedTheBarrierIsNamedAsSuch() {
        String message = ConcurrencyRunner.roundTimeoutMessage(4, 1, 2, 500);

        assertTrue(message.contains("1/4 threads completed within 500ms"), message);
        assertTrue(message.contains("2 worker(s) never got past the start barrier"), message);
    }

    @Test
    void aRoundThatFinishedJustAfterItsDeadlineSaysSo() {
        String message = ConcurrencyRunner.roundTimeoutMessage(2, 2, 2, 34);

        assertTrue(message.contains("all 2 workers finished just after"), message);
        assertFalse(message.contains("2/2 threads completed"),
                "\"2/2 completed\" beside \"may be stuck\" contradicted itself: " + message);
    }

    @Test
    void attachedWorkerFailuresAreStillAnnounced() {
        assertTrue(ConcurrencyRunner.attachedFailures(1)
                .contains("1 worker failure(s) from this round are attached as suppressed"));
        assertTrue(ConcurrencyRunner.attachedFailures(0).isEmpty(), "nothing to announce without failures");
    }

    // ---- the run's headline ----

    @Test
    void aRunThatWasProgressingWhenTheBudgetRanOutSaysSo() {
        // 24 rounds in 980 ms, about 41 ms each, and the next round had 20 ms.
        String headline = new ConcurrencyRunner.RunProgress(24, 40, 20).headline(1000);

        assertTrue(headline.startsWith("Test timed out after 1000ms"), headline);
        assertTrue(headline.contains("24 of 40 rounds completed in 980 ms"), headline);
        assertTrue(headline.contains("raise it or lower invocations"), headline);
        assertFalse(headline.contains("Possible deadlock"), headline);
    }

    @Test
    void aRunWhoseBudgetRanOutBetweenRoundsSaysSo() {
        String headline = new ConcurrencyRunner.RunProgress(25, 40, 0).headline(1000);

        assertTrue(headline.contains("25 of 40 rounds completed"), headline);
        assertFalse(headline.contains("Possible deadlock"), headline);
    }

    @Test
    void aRoundThatHadARealShareAndHungIsAPossibleDeadlock() {
        // Ten rounds of about 10 ms each, then a round with 900 ms left that did not finish.
        String headline = new ConcurrencyRunner.RunProgress(10, 40, 900).headline(1000);

        assertTrue(headline.contains("Possible deadlock, starvation, or visibility issue"), headline);
        assertTrue(headline.contains("10 of 40 rounds completed"), headline);
    }

    @Test
    void aFirstRoundThatHungIsAPossibleDeadlock() {
        String headline = new ConcurrencyRunner.RunProgress(0, 1, 300).headline(300);

        assertTrue(headline.contains("Possible deadlock"), headline);
    }
}
